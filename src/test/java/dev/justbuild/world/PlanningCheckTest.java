package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;
import dev.justbuild.plan.*;
import dev.justbuild.prompt.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
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

    @Test void theZoneFindingNamesItsBoxCountAndCrossingOperations() {
        var plan = new BuildPlan("Wall", "", List.of(
                Operation.fill("stone", new Cell(0, 0, 0), new Cell(3, 0, 0), false),
                Operation.fill("stone", new Cell(0, 1, 0), new Cell(6, 1, 0), false)));
        var expected = new PlanningFeedback.Problem(PlanningFeedback.Kind.ZONE, new Cell(4, 1, 0), new Cell(6, 1, 0), 3, List.of(1));
        // Facing north the plan frame is the world; facing east the plan turns clockwise, and so does the zone.
        var north = zoneCheck(plan, BuildZone.between(Level.OVERWORLD, new BlockPos(0, -1, -2), new BlockPos(3, -1, 2)), Direction.NORTH);
        assertEquals(List.of(expected), zones(north));
        var east = zoneCheck(plan, BuildZone.between(Level.OVERWORLD, new BlockPos(-2, -1, 0), new BlockPos(2, -1, 3)), Direction.EAST);
        assertEquals(List.of(expected), zones(east));
    }

    @Test void theZoneFindingSkipsOperationsThatOnlyOverlapItsBox() {
        // Operations 0 and 1 cross opposite sides of the zone; operation 2 sits inside the box between them.
        var plan = new BuildPlan("Wall", "", List.of(
                Operation.fill("stone", new Cell(-2, 1, 0), new Cell(3, 1, 0), false),
                Operation.fill("stone", new Cell(0, 2, 0), new Cell(6, 2, 0), false),
                Operation.set("oak_planks", new Cell(2, 1, 0))));
        var zone = BuildZone.between(Level.OVERWORLD, new BlockPos(0, -1, -2), new BlockPos(3, -1, 2));
        assertEquals(List.of(new PlanningFeedback.Problem(PlanningFeedback.Kind.ZONE, new Cell(-2, 1, 0), new Cell(6, 2, 0), 5, List.of(0, 1))),
                zones(zoneCheck(plan, zone, Direction.NORTH)));
    }

    @Test void aFoundationBelowAZonesFloorIsNamed() {
        // The foundation fills from y = 4 down to the stone at y = -1; the zone is y = 2..10. The pad at y = 5 shares
        // the foundation's columns but not their outside heights, so it isn't named; the marker above the zone is.
        var plan = new BuildPlan("Pad", "", List.of(
                Operation.fill("stone", new Cell(0, 5, 0), new Cell(1, 5, 1), false),
                Operation.foundation("cobblestone", new Cell(0, 4, 0), new Cell(1, 4, 1)),
                Operation.set("stone", new Cell(3, 11, 3))));
        var zone = BuildZone.between(Level.OVERWORLD, new BlockPos(-2, -1, -2), new BlockPos(3, -1, 3)).height(2, 10);
        assertEquals(List.of(new PlanningFeedback.Problem(PlanningFeedback.Kind.ZONE, new Cell(0, 0, 0), new Cell(3, 11, 3), 9, List.of(1, 2))),
                zones(zoneCheck(plan, zone, Direction.NORTH)));
    }

    private static List<PlanningFeedback.Problem> zones(PlanningCheck.Result result) {
        return result.feedback().problems().stream().filter(p -> p.kind() == PlanningFeedback.Kind.ZONE).toList();
    }

    private PlanningCheck.Result zoneCheck(BuildPlan plan, BuildZone zone, Direction facing) {
        return PlanningCheck.check(plan, BlockPos.ZERO, facing, LevelHeightAccessor.create(-64, 384),
                p -> p.getY() < 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Map.of(), Map.of(), false,
                p -> true, zone, BuildConstraints.NONE, new PlanLimits(100, 1000, 32));
    }

    private PlanningCheck.Result check(BuildPlan plan, java.util.function.Predicate<BlockPos> allowed) {
        return PlanningCheck.check(plan, BlockPos.ZERO, Direction.NORTH, LevelHeightAccessor.create(-64, 384),
                p -> p.getY() < 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Map.of(), Map.of(), false,
                allowed, null, BuildConstraints.NONE, new PlanLimits(100, 1000, 32));
    }
}
