package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.Operation;
import dev.justbuild.plan.PlanException;
import dev.justbuild.plan.PlanRotation;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BuildZoneTest {
    @BeforeAll
    static void bootstrap() {
        McBootstrap.init();
    }

    @Test
    void footprintIsInclusiveIgnoresCornerHeightAndRotatesIntoPlanFrame() throws Exception {
        var zone = BuildZone.between(Level.OVERWORLD, new BlockPos(12, 60, 15), new BlockPos(8, 80, 9));
        BlockPos origin = new BlockPos(10, 64, 10);
        for (Direction facing : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            var relative = zone.relativeTo(origin, facing);
            var turn = PlanRotation.forFacing(facing.getName());
            for (int x : new int[] {relative.minX(), relative.maxX()}) {
                for (int z : new int[] {relative.minZ(), relative.maxZ()}) {
                    Cell offset = turn.apply(new Cell(x, 0, z));
                    assertTrue(zone.contains(origin.offset(offset.x(), offset.y(), offset.z())));
                }
            }
        }
        zone.check(Level.OVERWORLD, List.of(new BlockPos(8, -64, 9), new BlockPos(12, 319, 15)));
        assertThrows(PlanException.class, () -> zone.check(Level.NETHER, List.of()));
        assertThrows(PlanException.class, () -> zone.check(Level.OVERWORLD, List.of(new BlockPos(13, 64, 15))));
        assertThrows(IllegalArgumentException.class,
                () -> BuildZone.between(Level.OVERWORLD, BlockPos.ZERO, new BlockPos(256, 0, 0)));
    }

    @Test
    void expansionPreservesOldAreaAndHeightAndNeverWrapsOrExceedsTheLimit() {
        var zone = BuildZone.between(Level.OVERWORLD, new BlockPos(-2, 60, -3), new BlockPos(2, 70, 3));
        assertEquals(new BuildZone(Level.OVERWORLD, -6, -7, 6, 7, 61), zone.expandedBy(4));
        assertEquals(new BuildZone(Level.OVERWORLD, -2, -8, 9, 3, 61),
                zone.including(List.of(new BlockPos(9, 200, -8), BlockPos.ZERO)));
        assertEquals(zone, zone.including(List.of(BlockPos.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> zone.expandedBy(0));
        assertThrows(IllegalArgumentException.class, () -> zone.expandedBy(-1));
        assertThrows(IllegalArgumentException.class, () -> zone.expandedBy(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> zone.expandedBy(126));
        assertThrows(IllegalArgumentException.class, () -> zone.including(List.of(new BlockPos(254, 0, 0))));
        var edge = new BuildZone(Level.OVERWORLD, -30_000_000, 0, -30_000_000, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> edge.expandedBy(1));
    }

    @Test
    void completingABedCannotSneakAcrossTheBoundary() throws Exception {
        var plan = new BuildPlan("Bed", "", List.of(Operation.set("red_bed[facing=east,part=foot]", new Cell(0, 0, 0))));
        var result = PlanPipeline.run(plan, new BlockPos(0, 64, 0), Direction.NORTH,
                LevelHeightAccessor.create(-64, 384), p -> Blocks.AIR.defaultBlockState(), 100);
        assertEquals(2, result.placements().size());
        var zone = BuildZone.between(Level.OVERWORLD, BlockPos.ZERO, BlockPos.ZERO);
        assertThrows(PlanException.class, () -> zone.check(Level.OVERWORLD, result.placements().keySet()));
    }

    @Test
    void editsCheckChangesIncludingRemovalsButAllowUnchangedBlocksOutside() throws Exception {
        var inside = new BlockPos(0, 64, 0);
        var outside = new BlockPos(1, 64, 0);
        var before = Map.of(inside, Blocks.STONE.defaultBlockState(), outside, Blocks.STONE.defaultBlockState());
        var after = Map.of(inside, Blocks.GOLD_BLOCK.defaultBlockState(), outside, Blocks.STONE.defaultBlockState());
        var baseline = Map.of(inside, Blocks.AIR.defaultBlockState(), outside, Blocks.AIR.defaultBlockState());
        var zone = BuildZone.between(Level.OVERWORLD, BlockPos.ZERO, BlockPos.ZERO);
        zone.check(Level.OVERWORLD, Diff.between(before, after, baseline).keySet());
        var removal = Diff.between(before, Map.of(inside, Blocks.STONE.defaultBlockState()), baseline);
        assertThrows(PlanException.class, () -> zone.check(Level.OVERWORLD, removal.keySet()));
    }
}
