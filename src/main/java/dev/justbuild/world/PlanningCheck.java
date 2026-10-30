package dev.justbuild.world;

import dev.justbuild.plan.*;
import dev.justbuild.prompt.*;
import dev.justbuild.relay.PromptCheck;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Dry-run validation and bounded scene capture. The caller owns server-thread reads and all world mutations. */
public final class PlanningCheck {
    public record Result(PlanningFeedback feedback, Map<BlockPos, BlockState> changes) { }

    private PlanningCheck() { }

    public static Result check(BuildPlan plan, BlockPos origin, Direction facing, LevelHeightAccessor height,
            Function<BlockPos, BlockState> live, Map<BlockPos, BlockState> baseline, Map<BlockPos, BlockState> before,
            boolean edit, Predicate<BlockPos> allowed, BuildZone zone, BuildConstraints constraints, PlanLimits limits) {
        return check(plan, origin, facing, height, live, baseline, before, edit, allowed, zone, constraints, limits, null);
    }

    /** routes is the dimension for route findings (reviewed requests only), or null to skip them. */
    public static Result check(BuildPlan plan, BlockPos origin, Direction facing, LevelHeightAccessor height,
            Function<BlockPos, BlockState> live, Map<BlockPos, BlockState> baseline, Map<BlockPos, BlockState> before,
            boolean edit, Predicate<BlockPos> allowed, BuildZone zone, BuildConstraints constraints, PlanLimits limits,
            ResourceKey<Level> routes) {
        List<PlanningFeedback.Problem> problems = new ArrayList<>();
        Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
        boolean[] unknown = {false};
        Function<BlockPos, BlockState> loaded = p -> {
            var state = live.apply(p);
            if (state == null) { unknown[0] = true; return Blocks.AIR.defaultBlockState(); }
            return state;
        };
        boolean validGeometry = true;
        try { limits.check(plan); }
        catch (PlanException invalid) { problems.add(problem(PlanningFeedback.Kind.LIMIT)); validGeometry = false; }
        if (validGeometry) try {
            var result = PlanPipeline.run(plan, origin, facing, height,
                    p -> edit && baseline.containsKey(p) ? baseline.get(p) : loaded.apply(p), limits.maxBlocks());
            if (!result.invalid().isEmpty()) problems.add(problem(PlanningFeedback.Kind.INVALID_BLOCKS));
            if (result.outsideWorld() > 0) problems.add(problem(PlanningFeedback.Kind.OUTSIDE_WORLD));
            changes.putAll(edit ? Diff.between(before, result.placements(), baseline) : result.placements());
            changes.keySet().removeIf(p -> !allowed.test(p));
            PlanRotation inverse = PlanRotation.forFacing(facing.getName()).inverse();
            if (zone != null) {
                List<BlockPos> outside = changes.keySet().stream().filter(p -> !zone.contains(p)).toList();
                if (!outside.isEmpty()) problems.add(zoneProblem(plan, outside, origin, inverse));
            }
            try { constraints.check(origin, changes, before); }
            catch (PlanException e) { problems.add(problem(PlanningFeedback.Kind.CONSTRAINT)); }
            for (var issue : BuildQuality.details(changes, loaded)) {
                Cell position = issue.position() == null ? null : planCell(issue.position(), origin, inverse);
                problems.add(new PlanningFeedback.Problem(PlanningFeedback.Kind.valueOf(issue.kind().name()), position));
            }
        } catch (PlanTooLargeException | PlanTooMuchWorkException e) {
            problems.add(problem(PlanningFeedback.Kind.LIMIT));
        } catch (PlanException | IllegalArgumentException e) {
            problems.add(problem(PlanningFeedback.Kind.INVALID_BLOCKS));
        }
        if (unknown[0]) problems.add(problem(PlanningFeedback.Kind.UNKNOWN));
        Set<BlockPos> positions = new HashSet<>(changes.keySet()); positions.addAll(before.keySet());
        Function<BlockPos, BlockState> drafted = p -> changes.containsKey(p) ? changes.get(p) : live.apply(p);
        // Missing blocks would show up as missing stairs.
        boolean complete = problems.stream().noneMatch(p -> p.kind() == PlanningFeedback.Kind.INVALID_BLOCKS || p.kind() == PlanningFeedback.Kind.OUTSIDE_WORLD);
        if (routes != null && !changes.isEmpty() && complete) {
            // Air placements (cleared interiors) would enlarge the box, moving doors off its edge and flat roofs out of its top layer.
            List<BlockPos> built = new ArrayList<>();
            changes.forEach((p, state) -> { if (!state.isAir()) built.add(p); });
            before.forEach((p, state) -> { if (!changes.getOrDefault(p, state).isAir()) built.add(p); });
            // Route findings come last, so the 32-finding cap can only ever cut them.
            problems.addAll(RouteFindings.find(routes, built, drafted, origin, facing));
        }
        InspectionRequest box = WorldInspector.around(positions, origin, facing);
        VoxelVolume source = capture(box, origin, facing, live, VoxelVolume.MAX_CELLS, false);
        VoxelVolume candidate = changes.isEmpty() ? null : capture(box, origin, facing, drafted, VoxelVolume.MAX_CELLS, false);
        return new Result(new PlanningFeedback(PromptCheck.planKey(PlanWriter.write(plan)),
                problems.stream().limit(32).toList(), source, candidate, null, null), PositionMaps.frozen(changes));
    }

    private static PlanningFeedback.Problem problem(PlanningFeedback.Kind kind) {
        return new PlanningFeedback.Problem(kind, null);
    }

    /** A world position in the plan frame: relative to the origin, with the facing's turn undone. */
    static Cell planCell(BlockPos p, BlockPos origin, PlanRotation inverse) {
        return inverse.apply(new Cell(p.getX() - origin.getX(), p.getY() - origin.getY(), p.getZ() - origin.getZ()));
    }

    /**
     * The ZONE finding: the plan-frame box and count of changes outside the zone, and up to 8 operations, in plan order,
     * whose reach covers a column of those changes at their heights. The list is a hint: an operation can be named for
     * a column its shape skips, or for the inside stretch of a column that leaves through both the zone's floor and its
     * ceiling, and changes no operation covers (door tops, bed heads, an edit's restored blocks) name none.
     */
    private static PlanningFeedback.Problem zoneProblem(BuildPlan plan, List<BlockPos> outside, BlockPos origin, PlanRotation inverse) {
        // Each outside column in the plan frame: {x, z, lowest y, highest y}.
        Map<Long, int[]> columns = new HashMap<>();
        int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0, x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
        for (BlockPos p : outside) {
            Cell c = planCell(p, origin, inverse);
            x0 = Math.min(x0, c.x()); y0 = Math.min(y0, c.y()); z0 = Math.min(z0, c.z());
            x1 = Math.max(x1, c.x()); y1 = Math.max(y1, c.y()); z1 = Math.max(z1, c.z());
            int[] column = columns.computeIfAbsent(((long) c.x() << 32) | (c.z() & 0xffffffffL), k -> new int[] {c.x(), c.z(), c.y(), c.y()});
            column[2] = Math.min(column[2], c.y()); column[3] = Math.max(column[3], c.y());
        }
        Bounds box = new Bounds(x0, y0, z0, x1, y1, z1);
        List<Integer> operations = new ArrayList<>();
        for (int i = 0; i < plan.operations().size() && i <= PlanningFeedback.Problem.LAST_OPERATION
                && operations.size() < PlanningFeedback.Problem.MAX_OPERATIONS; i++) {
            Bounds reach = Bounds.touched(plan.operations().get(i));
            if (!overlaps(reach, box)) continue;
            for (int[] c : columns.values())
                if (c[0] >= reach.minX() && c[0] <= reach.maxX() && c[1] >= reach.minZ() && c[1] <= reach.maxZ()
                        && c[2] <= reach.maxY() && c[3] >= reach.minY()) { operations.add(i); break; }
        }
        try {
            return new PlanningFeedback.Problem(PlanningFeedback.Kind.ZONE, new Cell(x0, y0, z0), new Cell(x1, y1, z1),
                    Math.min(outside.size(), PlanningFeedback.Problem.MAX_COUNT), operations);
        } catch (IllegalArgumentException farAway) { return problem(PlanningFeedback.Kind.ZONE); }
    }

    private static boolean overlaps(Bounds a, Bounds b) {
        return a.minX() <= b.maxX() && b.minX() <= a.maxX() && a.minY() <= b.maxY() && b.minY() <= a.maxY()
                && a.minZ() <= b.maxZ() && b.minZ() <= a.maxZ();
    }

    public static VoxelVolume capture(InspectionRequest box, BlockPos origin, Direction facing,
            Function<BlockPos, BlockState> live, int budget, boolean exact) {
        PlanRotation rotation = PlanRotation.forFacing(facing.getName());
        var inverse = net.minecraft.world.level.block.Rotation.valueOf(rotation.inverse().name());
        return VoxelVolume.capture(box, budget, exact, c -> {
            Cell offset = rotation.apply(c);
            var state = live.apply(origin.offset(offset.x(), offset.y(), offset.z()));
            return state == null ? null : BlockStateParser.serialize(state.rotate(inverse));
        });
    }
}
