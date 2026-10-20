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
            if (zone != null && changes.keySet().stream().anyMatch(p -> !zone.contains(p)))
                problems.add(problem(PlanningFeedback.Kind.ZONE));
            try { constraints.check(origin, changes, before); }
            catch (PlanException e) { problems.add(problem(PlanningFeedback.Kind.CONSTRAINT)); }
            PlanRotation inverse = PlanRotation.forFacing(facing.getName()).inverse();
            for (var issue : BuildQuality.details(changes, loaded)) {
                Cell position = issue.position() == null ? null : inverse.apply(new Cell(issue.position().getX() - origin.getX(),
                        issue.position().getY() - origin.getY(), issue.position().getZ() - origin.getZ()));
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
