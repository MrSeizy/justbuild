package dev.justbuild.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

/** Bounded, advisory standing-space graph. Wooden doors are treated as openable; jumps and ladders are not routes. */
public final class Walkability {
    public static final int MAX_CELLS = 131_072, MAX_MARKERS = 256;
    public record Result(List<String> lines, List<BlockPos> markers, int spaces, int reachable, int unreachable) {
        public Result { lines = List.copyOf(lines); markers = List.copyOf(markers); }
    }
    private record Column(int x, int z) { }
    /** A standing space: where the feet are, the block they stand on, and that block's facing when it is a stair. */
    record Node(BlockPos pos, BlockPos floor, double feet, Direction stairs) { }
    record Routes(Result report, Set<BlockPos> standing, Set<BlockPos> reached, Set<BlockPos> doors,
            BlockPos start, boolean complete, Map<BlockPos, Node> nodes, boolean steps) {
        Routes {
            standing = Set.copyOf(standing); reached = Set.copyOf(reached); doors = Set.copyOf(doors); nodes = Map.copyOf(nodes);
        }
    }
    private Walkability() { }

    /** The reader returns null for unloaded cells. No chunks are requested or world state changed. */
    public static Result inspect(BuildRegion bounds, Function<BlockPos, BlockState> read) {
        return routes(bounds, read, null, false).report();
    }

    /** Review comparisons keep the first graph's start, so a new disconnected entrance cannot hide a regression. */
    static Routes routes(BuildRegion bounds, Function<BlockPos, BlockState> read, BlockPos fixedStart, boolean review) {
        return routes(bounds, read, fixedStart, review, false);
    }

    /** steps: one-block rises connect too (players can jump them); the extra headroom a jump needs is ignored. */
    static Routes routes(BuildRegion bounds, Function<BlockPos, BlockState> read, BlockPos fixedStart, boolean review, boolean steps) {
        long volume = (long) (bounds.max().getX() - bounds.min().getX() + 3)
                * (bounds.max().getY() - bounds.min().getY() + 3) * (bounds.max().getZ() - bounds.min().getZ() + 3);
        if (volume > MAX_CELLS) return new Routes(new Result(List.of("This area is too large for a route check. Select a smaller part first."),
                List.of(), 0, 0, 0), Set.of(), Set.of(), Set.of(), null, false, Map.of(), steps);
        Map<BlockPos, BlockState> cache = new HashMap<>();
        Function<BlockPos, BlockState> live = p -> {
            if (!cache.containsKey(p)) cache.put(p.immutable(), read.apply(p));
            return cache.get(p);
        };
        Map<BlockPos, Node> nodes = new HashMap<>();
        Map<Column, List<Node>> columns = new HashMap<>();
        List<BlockPos> entrances = new ArrayList<>();
        for (BlockPos mutable : BlockPos.betweenClosed(bounds.min().offset(-1, -1, -1), bounds.max().offset(1, 0, 1))) {
            BlockPos p = mutable.immutable(); BlockState state = live.apply(p);
            if (state == null) continue;
            if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                    && (review || p.getX() <= bounds.min().getX() + 1 || p.getX() >= bounds.max().getX() - 1
                        || p.getZ() <= bounds.min().getZ() + 1 || p.getZ() >= bounds.max().getZ() - 1)) entrances.add(p);
            if (state.isAir() || !state.getFluidState().isEmpty() || state.getBlock() instanceof DoorBlock) continue;
            var shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, p);
            if (shape.isEmpty()) continue;
            double height = shape.max(Direction.Axis.Y);
            if (height <= 0 || height > 1.001) continue;
            double feet = p.getY() + height;
            // Roof surfaces are omitted: this check concerns rooms beneath the recorded top boundary.
            if (feet > bounds.max().getY() - 1 || !headroom(p.getX(), feet, p.getZ(), live)) continue;
            BlockPos pos = BlockPos.containing(p.getX(), feet + 0.001, p.getZ());
            Node node = new Node(pos, p, feet, state.getBlock() instanceof StairBlock ? state.getValue(StairBlock.FACING) : null);
            if (nodes.putIfAbsent(pos, node) == null) columns.computeIfAbsent(new Column(pos.getX(), pos.getZ()), k -> new ArrayList<>()).add(node);
        }
        if (nodes.isEmpty()) return new Routes(new Result(List.of("No standing spaces with enough headroom were found. Check floors and ceilings."),
                List.of(), 0, 0, 0), Set.of(), Set.of(), Set.of(), null, cache.values().stream().noneMatch(s -> s == null), Map.of(), steps);
        List<String> lines = new ArrayList<>(); List<BlockPos> blocked = new ArrayList<>();
        entrances.sort(Comparator.<BlockPos>comparingInt(p -> p.getY()).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        Node start = null;
        for (BlockPos door : entrances) {
            Node candidate = nodes.get(door);
            if (candidate == null) blocked.add(door);
            else if (start == null) start = candidate;
        }
        if (fixedStart != null) start = nodes.get(fixedStart);
        else if (start == null) {
            start = nodes.values().stream().filter(n -> n.pos().getX() <= bounds.min().getX() || n.pos().getX() >= bounds.max().getX()
                            || n.pos().getZ() <= bounds.min().getZ() || n.pos().getZ() >= bounds.max().getZ())
                    .min(Comparator.comparingDouble(Node::feet).thenComparingInt(n -> n.pos().getX()).thenComparingInt(n -> n.pos().getZ()))
                    .orElseGet(() -> nodes.values().stream().min(Comparator.comparingDouble(Node::feet)
                            .thenComparingInt(n -> n.pos().getX()).thenComparingInt(n -> n.pos().getZ())).orElseThrow());
            lines.add("No usable outside door found; the lowest exposed floor is the starting point.");
        }
        Set<BlockPos> reached = new HashSet<>(); ArrayDeque<Node> queue = new ArrayDeque<>();
        if (start != null) { queue.add(start); reached.add(start.pos()); }
        while (!queue.isEmpty()) {
            Node at = queue.removeFirst();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = at.pos().relative(direction);
                for (Node candidate : columns.getOrDefault(new Column(next.getX(), next.getZ()), List.of())) {
                    if (!reached.contains(candidate.pos()) && connected(at, candidate, direction, steps)) {
                        reached.add(candidate.pos()); queue.addLast(candidate);
                    }
                }
            }
        }
        List<BlockPos> interior = nodes.keySet().stream().filter(p -> isInterior(bounds, p)).sorted(Comparator.<BlockPos>comparingInt(p -> p.getY())
                        .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).toList();
        List<BlockPos> unreachable = interior.stream().filter(p -> !reached.contains(p)).toList();
        int reachable = interior.size() - unreachable.size();
        lines.add(start == null ? "The previous route entrance is no longer usable."
                : "From " + start.pos().toShortString() + ": " + reachable + " of " + interior.size() + " interior standing spaces are reachable.");
        if (!blocked.isEmpty()) lines.add(blocked.size() + " outside doorway(s) have no usable standing space, first at " + blocked.getFirst().toShortString() + ".");
        if (!unreachable.isEmpty()) {
            String floors = unreachable.stream().map(BlockPos::getY).distinct().limit(8).map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
            lines.add(unreachable.size() + " unreachable spaces on floor heights " + floors + ". Red markers show examples; check doors and stairs between rooms.");
        } else lines.add("All detected interior standing spaces connect to the starting point.");
        if (cache.values().stream().anyMatch(s -> s == null)) lines.add("Some nearby cells were unloaded; move closer and check again.");
        lines.add("Advisory: wooden doors are openable; routes need headroom and walking steps. Ladders, jumps, and redstone doors need manual inspection.");
        List<BlockPos> markers = new ArrayList<>(blocked.stream().limit(MAX_MARKERS).toList());
        int remaining = MAX_MARKERS - markers.size();
        if (remaining > 0) for (int i = 0; i < Math.min(remaining, unreachable.size()); i++) {
            markers.add(unreachable.get((int) ((long) i * unreachable.size() / Math.min(remaining, unreachable.size()))));
        }
        return new Routes(new Result(lines, markers, interior.size(), reachable, unreachable.size()), nodes.keySet(), reached,
                entrances.stream().filter(reached::contains).collect(java.util.stream.Collectors.toSet()),
                start == null ? null : start.pos(), cache.values().stream().noneMatch(s -> s == null), nodes, steps);
    }

    /** Strictly inside the region's columns: the edge columns may be outside walls or terrain. */
    static boolean isInterior(BuildRegion bounds, BlockPos p) {
        return p.getX() > bounds.min().getX() && p.getX() < bounds.max().getX()
                && p.getZ() > bounds.min().getZ() && p.getZ() < bounds.max().getZ();
    }

    /** Unreached counted spaces, grouped into areas a player could walk around inside by the same step rules. */
    static List<List<Node>> unreachedAreas(Routes routes, Predicate<Node> counted) {
        Map<Column, List<Node>> columns = new HashMap<>();
        List<Node> ordered = new ArrayList<>();
        for (Node n : routes.nodes().values()) if (!routes.reached().contains(n.pos()) && counted.test(n)) {
            columns.computeIfAbsent(new Column(n.pos().getX(), n.pos().getZ()), k -> new ArrayList<>()).add(n);
            ordered.add(n);
        }
        ordered.sort(Comparator.<Node>comparingInt(n -> n.pos().getY()).thenComparingInt(n -> n.pos().getX())
                .thenComparingInt(n -> n.pos().getZ()));
        Set<BlockPos> seen = new HashSet<>(); List<List<Node>> areas = new ArrayList<>();
        for (Node first : ordered) {
            if (!seen.add(first.pos())) continue;
            List<Node> area = new ArrayList<>(); ArrayDeque<Node> queue = new ArrayDeque<>(List.of(first));
            while (!queue.isEmpty()) {
                Node at = queue.removeFirst(); area.add(at);
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    BlockPos next = at.pos().relative(direction);
                    for (Node candidate : columns.getOrDefault(new Column(next.getX(), next.getZ()), List.of()))
                        if (!seen.contains(candidate.pos()) && connected(at, candidate, direction, routes.steps())) { seen.add(candidate.pos()); queue.addLast(candidate); }
                }
            }
            areas.add(area);
        }
        return areas;
    }

    private static boolean connected(Node a, Node b, Direction direction, boolean steps) {
        double rise = b.feet() - a.feet();
        if (Math.abs(rise) <= (steps ? 1.001 : 0.601)) return true;
        if (Math.abs(rise) > 1.001) return false;
        Direction uphill = rise > 0 ? direction : direction.getOpposite();
        return a.stairs() == uphill || b.stairs() == uphill;
    }

    private static boolean headroom(int x, double feet, int z, Function<BlockPos, BlockState> read) {
        AABB body = new AABB(0.21, feet + 0.001, 0.21, 0.79, feet + 1.8, 0.79);
        for (int y = (int) Math.floor(feet); y <= (int) Math.floor(feet + 1.799); y++) {
            BlockPos p = new BlockPos(x, y, z); BlockState state = read.apply(p);
            if (state == null || !state.getFluidState().isEmpty()) return false;
            if (state.getBlock() instanceof DoorBlock) {
                if (state.is(Blocks.IRON_DOOR) && !state.getValue(DoorBlock.OPEN)) return false;
                continue;
            }
            for (AABB shape : state.getCollisionShape(EmptyBlockGetter.INSTANCE, p).toAabbs()) {
                if (shape.move(0, y, 0).intersects(body)) return false;
            }
        }
        return true;
    }
}
