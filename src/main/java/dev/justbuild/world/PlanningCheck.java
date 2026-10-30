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
            if (zone != null) {
                List<BlockPos> outside = changes.keySet().stream().filter(p -> !zone.contains(p)).toList();
                if (!outside.isEmpty()) problems.add(zoneProblem(plan, outside, zone, origin, facing));
            }
            try { constraints.check(origin, changes, before); }
            catch (PlanException e) { problems.add(problem(PlanningFeedback.Kind.CONSTRAINT)); }
            PlanRotation inverse = PlanRotation.forFacing(facing.getName()).inverse();
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
        InspectionRequest box = WorldInspector.around(positions, origin, facing);
        VoxelVolume source = capture(box, origin, facing, live, VoxelVolume.MAX_CELLS, false);
        VoxelVolume candidate = changes.isEmpty() ? null : capture(box, origin, facing,
                p -> changes.containsKey(p) ? changes.get(p) : live.apply(p), VoxelVolume.MAX_CELLS, false);
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
     * The ZONE finding: the plan-frame box and count of changes outside the zone, and up to 8 operations whose bounds
     * reach outside the zone and overlap that box. Bounds cover every cell an operation could touch, so the list is approximate.
     */
    private static PlanningFeedback.Problem zoneProblem(BuildPlan plan, List<BlockPos> outside, BuildZone zone,
            BlockPos origin, Direction facing) {
        PlanRotation inverse = PlanRotation.forFacing(facing.getName()).inverse();
        int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0, x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
        for (BlockPos p : outside) {
            Cell c = planCell(p, origin, inverse);
            x0 = Math.min(x0, c.x()); y0 = Math.min(y0, c.y()); z0 = Math.min(z0, c.z());
            x1 = Math.max(x1, c.x()); y1 = Math.max(y1, c.y()); z1 = Math.max(z1, c.z());
        }
        Bounds box = new Bounds(x0, y0, z0, x1, y1, z1);
        List<Integer> operations = new ArrayList<>();
        BuildArea area;
        try { area = zone.relativeTo(origin, facing); } catch (IllegalArgumentException farAway) { area = null; }
        for (int i = 0; area != null && i < plan.operations().size() && i <= PlanningFeedback.Problem.LAST_OPERATION
                && operations.size() < PlanningFeedback.Problem.MAX_OPERATIONS; i++) {
            Bounds reach;
            try { reach = Bounds.of(plan.operations().get(i)); } catch (RuntimeException unbounded) { continue; }
            if (overlaps(reach, box) && leaves(reach, area)) operations.add(i);
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

    /** Whether a box reaches outside the area. A polygon has no holes, so testing the box's border cells is enough. */
    private static boolean leaves(Bounds b, BuildArea area) {
        if (b.minX() < area.minX() || b.maxX() > area.maxX() || b.minZ() < area.minZ() || b.maxZ() > area.maxZ()
                || area.minY() != null && (b.minY() < area.minY() || b.maxY() > area.maxY())) return true;
        for (int x = b.minX(); x <= b.maxX(); x++)
            if (!Footprint.contains(area.polygon(), x, b.minZ()) || !Footprint.contains(area.polygon(), x, b.maxZ())) return true;
        for (int z = b.minZ(); z <= b.maxZ(); z++)
            if (!Footprint.contains(area.polygon(), b.minX(), z) || !Footprint.contains(area.polygon(), b.maxX(), z)) return true;
        return false;
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
