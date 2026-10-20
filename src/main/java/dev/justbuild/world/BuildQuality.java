package dev.justbuild.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;

/** Bounded advisory checks of the proposed result. Intentional decoration may trigger a warning. */
public final class BuildQuality {
    /** Plans are already bounded by maxBlocks; this only guards callers outside the plan limits. */
    private static final int MAX_BLOCKS = dev.justbuild.config.JustBuildConfig.MAX_BLOCKS_LIMIT;
    public enum Kind { DOOR, STAIR, SUPPORT, FOUNDATION, LIMIT }
    public record Issue(Kind kind, String message, BlockPos position, String repair) {
        public String id() { return kind.name() + (position == null ? "" : ":" + position.getX() + "," + position.getY() + "," + position.getZ()); }
    }
    private BuildQuality() { }

    public static List<String> inspect(Map<BlockPos, BlockState> changes, Function<BlockPos, BlockState> live) {
        return details(changes, live).stream().map(Issue::message).toList();
    }

    public static List<Issue> details(Map<BlockPos, BlockState> changes, Function<BlockPos, BlockState> live) {
        if (changes.size() > MAX_BLOCKS) return List.of(new Issue(Kind.LIMIT, "Quality checks cover plans up to " + String.format(java.util.Locale.ROOT, "%,d", MAX_BLOCKS) + " blocks.", null, ""));
        Function<BlockPos, BlockState> after = p -> changes.containsKey(p) ? changes.get(p) : live.apply(p);
        List<Issue> warnings = new ArrayList<>();
        int doors = 0, stairs = 0;
        BlockPos firstDoor = null, firstStair = null;
        for (var entry : changes.entrySet()) {
            BlockPos p = entry.getKey(); BlockState s = entry.getValue();
            if (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                Direction facing = s.getValue(DoorBlock.FACING);
                boolean blocked = false;
                for (Direction side : List.of(facing, facing.getOpposite())) {
                    BlockPos approach = p.relative(side);
                    if (after.apply(approach).isSolidRender() || after.apply(approach.above()).isSolidRender()) blocked = true;
                }
                if (blocked) { doors++; if (earlier(p, firstDoor)) firstDoor = p; }
            }
            if (s.getBlock() instanceof StairBlock && s.getValue(StairBlock.HALF) == Half.BOTTOM) {
                BlockPos landing = p.relative(s.getValue(StairBlock.FACING)).above();
                BlockState at = after.apply(landing);
                if (!(at.getBlock() instanceof StairBlock) && (at.isSolidRender() || after.apply(landing.above()).isSolidRender()
                        || after.apply(landing.below()).isAir())) {
                    stairs++; if (earlier(p, firstStair)) firstStair = p;
                }
            }
        }
        if (doors > 0) warnings.add(new Issue(Kind.DOOR, doors + " doorway(s) may be blocked, first at " + firstDoor.toShortString() + ".",
                firstDoor, "Clear two-block headroom on both sides of this doorway."));
        if (stairs > 0) warnings.add(new Issue(Kind.STAIR, stairs + " stair(s) may lack a clear landing, first at " + firstStair.toShortString()
                + ". Decorative roof stairs may be intentional.", firstStair, "Clear this landing and support it if there is a short gap."));

        Set<BlockPos> unseen = new HashSet<>();
        changes.forEach((p, s) -> { if (!s.isAir() && s.getFluidState().isEmpty()) unseen.add(p); });
        int floating = 0;
        BlockPos firstFloating = null;
        while (!unseen.isEmpty()) {
            BlockPos start = unseen.iterator().next();
            unseen.remove(start);
            ArrayDeque<BlockPos> queue = new ArrayDeque<>(); queue.add(start);
            boolean supported = false;
            BlockPos bottom = start;
            while (!queue.isEmpty()) {
                BlockPos p = queue.removeFirst();
                if (earlier(p, bottom)) bottom = p;
                for (Direction d : Direction.values()) {
                    BlockPos next = p.relative(d);
                    if (unseen.remove(next)) queue.addLast(next);
                    else if (!changes.containsKey(next) && after.apply(next).isSolidRender()) supported = true;
                }
            }
            if (!supported) { floating++; if (earlier(bottom, firstFloating)) firstFloating = bottom; }
        }
        if (floating > 0) warnings.add(new Issue(Kind.SUPPORT, floating + " separate section(s) have no solid contact with existing blocks, near "
                + firstFloating.toShortString() + ". Check floating sections and foundations.", firstFloating,
                "Add a support column beneath this section, up to 16 blocks down."));

        Map<Long, Integer> bottoms = new LinkedHashMap<>();
        changes.forEach((p, s) -> {
            if (s.isSolidRender()) bottoms.merge(((long) p.getX() << 32) ^ (p.getZ() & 0xffffffffL), p.getY(), Math::min);
        });
        if (bottoms.size() >= 4) {
            int low = bottoms.values().stream().mapToInt(Integer::intValue).min().orElse(0);
            int high = bottoms.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            if (high - low >= 3) {
                long column = bottoms.entrySet().stream().filter(e -> e.getValue() == high).mapToLong(Map.Entry::getKey).min().orElseThrow();
                BlockPos location = new BlockPos((int) (column >> 32), high, (int) column);
                warnings.add(new Issue(Kind.FOUNDATION, "The bottom surface varies by " + (high - low)
                        + " blocks. Check that foundations meet the terrain where intended.", location,
                        "Extend this foundation column down to solid terrain, up to 16 blocks."));
            }
        }
        return List.copyOf(warnings);
    }
    private static boolean earlier(BlockPos p, BlockPos other) {
        return other == null || p.getY() < other.getY() || p.getY() == other.getY()
                && (p.getX() < other.getX() || p.getX() == other.getX() && p.getZ() < other.getZ());
    }

    /** A local candidate only. The caller checks ownership, locks, containers, zone, and limits before previewing it. */
    public static Map<BlockPos, BlockState> repair(Issue issue, Map<BlockPos, BlockState> changes,
            Function<BlockPos, BlockState> live) throws dev.justbuild.plan.PlanException {
        Function<BlockPos, BlockState> after = p -> changes.getOrDefault(p, live.apply(p));
        Map<BlockPos, BlockState> repair = new LinkedHashMap<>();
        BlockPos p = issue.position();
        if (p == null) throw new dev.justbuild.plan.PlanException("This warning has no automatic repair.");
        BlockState state = after.apply(p);
        if (issue.kind() == Kind.DOOR && state.getBlock() instanceof DoorBlock) {
            Direction facing = state.getValue(DoorBlock.FACING);
            for (Direction side : List.of(facing, facing.getOpposite())) {
                clear(p.relative(side), after, repair); clear(p.relative(side).above(), after, repair);
            }
        } else if (issue.kind() == Kind.STAIR && state.getBlock() instanceof StairBlock) {
            BlockPos landing = p.relative(state.getValue(StairBlock.FACING)).above();
            clear(landing, after, repair); clear(landing.above(), after, repair);
            if (after.apply(landing.below()).isAir()) support(landing, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), after, repair);
        } else if (issue.kind() == Kind.SUPPORT || issue.kind() == Kind.FOUNDATION) {
            support(p, state.isSolidRender() ? state : net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), after, repair);
        }
        if (repair.isEmpty()) throw new dev.justbuild.plan.PlanException("No bounded repair is available here. Inspect the highlighted location and edit it manually.");
        return Map.copyOf(repair);
    }
    private static void clear(BlockPos p, Function<BlockPos, BlockState> after, Map<BlockPos, BlockState> repair) {
        if (after.apply(p).isSolidRender()) repair.put(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    }
    private static void support(BlockPos start, BlockState material, Function<BlockPos, BlockState> after,
            Map<BlockPos, BlockState> repair) throws dev.justbuild.plan.PlanException {
        for (int distance = 1; distance <= 17; distance++) {
            BlockPos p = start.below(distance); BlockState state = after.apply(p);
            if (state.isSolidRender()) return;
            if (!state.isAir() || distance == 17) throw new dev.justbuild.plan.PlanException("No clear support path reaches solid terrain within 16 blocks.");
            repair.put(p, material);
        }
    }

}
