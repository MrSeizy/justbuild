package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;
import dev.justbuild.plan.*;
import dev.justbuild.prompt.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PlanningCheckTest {
    @BeforeAll static void bootstrap() { McBootstrap.init(); }

    @Test void detectsFloatingGeometryAndReflectsOnlyAllowedChanges() {
        var plan = new BuildPlan("Column", "", List.of(Operation.set("stone", new Cell(0, 4, 0))));
        var result = check(plan, p -> true);
        assertTrue(result.feedback().problems().stream().anyMatch(p -> p.kind() == PlanningFeedback.Kind.SUPPORT));
        assertNotNull(result.feedback().candidate());
        var blocked = check(plan, p -> false);
        assertTrue(blocked.changes().isEmpty()); assertNull(blocked.feedback().candidate());
        var fixed = check(new BuildPlan("Column", "", List.of(Operation.fill("stone", new Cell(0, 0, 0), new Cell(0, 4, 0), false))), p -> true);
        assertTrue(fixed.feedback().problems().isEmpty());
    }

    @Test void reportsInvalidBlocksAndHardLimitsWithoutPlacement() {
        var bad = check(new BuildPlan("Bad", "", List.of(Operation.set("minecraft:no_such_block", new Cell(0, 0, 0)))), p -> true);
        assertTrue(bad.feedback().technicalFailures() > 0); assertTrue(bad.changes().isEmpty());
        var huge = check(new BuildPlan("Huge", "", List.of(Operation.fill("stone", new Cell(0, 0, 0), new Cell(64, 64, 64), false))), p -> true);
        assertTrue(huge.feedback().technicalFailures() > 0); assertTrue(huge.changes().isEmpty());
    }

    @Test void exactCandidateInspectionPreservesFrameAndUnknownData() {
        var box = new InspectionRequest(new Cell(0, 0, -1), new Cell(0, 0, -1), InspectionRequest.Mode.EXACT, InspectionRequest.Target.CANDIDATE);
        var volume = PlanningCheck.capture(box, new BlockPos(10, 20, 30), Direction.EAST,
                p -> p.equals(new BlockPos(11, 20, 30)) ? Blocks.OAK_STAIRS.defaultBlockState() : null, 4096, true);
        assertNotNull(volume.at(0, 0, 0));
        assertEquals(volume, VoxelVolume.parse(volume.toJson()));
        var unknown = PlanningCheck.capture(box, BlockPos.ZERO, Direction.NORTH, p -> null, 4096, true);
        assertNull(unknown.at(0, 0, 0)); assertTrue(unknown.format().contains("-1"));
    }

    @Test void aDraftForAnUnloadedAreaNeedsNoCorrection() {
        var marker = new BuildPlan("Marker", "", List.of(Operation.fill("gold_block", new Cell(-1, 0, 0), new Cell(1, 0, 0), false)));
        var result = PlanningCheck.check(marker, BlockPos.ZERO, Direction.NORTH, LevelHeightAccessor.create(-64, 384),
                p -> null, Map.of(), Map.of(), false, p -> true, null, BuildConstraints.NONE, new PlanLimits(100, 1000, 32));
        var kinds = result.feedback().problems().stream().map(PlanningFeedback.Problem::kind).toList();
        assertTrue(kinds.contains(PlanningFeedback.Kind.UNKNOWN), kinds.toString());
        assertFalse(result.feedback().needsCorrection(), "problems reported: " + kinds);
    }

    private PlanningCheck.Result check(BuildPlan plan, java.util.function.Predicate<BlockPos> allowed) {
        return PlanningCheck.check(plan, BlockPos.ZERO, Direction.NORTH, LevelHeightAccessor.create(-64, 384),
                p -> p.getY() < 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Map.of(), Map.of(), false,
                allowed, null, BuildConstraints.NONE, new PlanLimits(100, 1000, 32));
    }
}
