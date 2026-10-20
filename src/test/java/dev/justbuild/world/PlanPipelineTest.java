package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.MirrorPlane;
import dev.justbuild.plan.Operation;
import dev.justbuild.plan.PlanRotation;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PlanPipelineTest {
    static final LevelHeightAccessor OVERWORLD = LevelHeightAccessor.create(-64, 384);
    static final PlanPipeline.WorldLookup EMPTY = pos -> Blocks.AIR.defaultBlockState();

    @BeforeAll
    static void bootstrap() {
        McBootstrap.init();
    }

    static PlanPipeline.Result run(Direction facing, PlanPipeline.WorldLookup world, Operation... ops) throws Exception {
        return PlanPipeline.run(new BuildPlan("t", "", List.of(ops)), new BlockPos(10, 64, 10), facing, OVERWORLD, world,
                100_000);
    }

    /** PlanRotation's cell math must agree with Minecraft's Rotation, or every build would be mirrored. */
    @Test
    void planRotationAgreesWithMinecraft() {
        for (PlanRotation turn : PlanRotation.values()) {
            Direction mc = Rotation.valueOf(turn.name()).rotate(Direction.NORTH);
            Cell ours = turn.apply(new Cell(0, 0, -1));
            assertEquals(new Cell(mc.getStepX(), mc.getStepY(), mc.getStepZ()), ours, turn.name());
            assertEquals(turn, PlanRotation.forFacing(mc.getName()));
        }
    }

    @Test
    void roofStairsTurnWithThePlayer() throws Exception {
        // Facing east (CLOCKWISE_90): the plan's north eave (stairs facing south) ends up facing west. Plan (1, 0, 0)
        // is an eave stair between the two gable rims; it lands at world (10, 64, 11).
        var result = run(Direction.EAST, EMPTY,
                Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(2, 0, 2), "gable", "x"));
        BlockState eave = result.placements().get(new BlockPos(10, 64, 11));
        assertEquals(Direction.WEST, eave.getValue(StairBlock.FACING));
        assertTrue(result.placements().get(new BlockPos(9, 65, 11)).is(Blocks.OAK_SLAB));
    }

    @Test
    void mirroredStairsFaceTheOtherWay() throws Exception {
        var result = run(Direction.NORTH, EMPTY,
                Operation.set("oak_stairs[facing=east]", new Cell(2, 0, 0)).withMirror(new MirrorPlane("x", 0)));
        assertEquals(Direction.EAST, result.placements().get(new BlockPos(12, 64, 10)).getValue(StairBlock.FACING));
        assertEquals(Direction.WEST, result.placements().get(new BlockPos(8, 64, 10)).getValue(StairBlock.FACING));
    }

    @Test
    void worldReadsGoThroughTheLookup() throws Exception {
        PlanPipeline.WorldLookup groundBelow62 = pos -> pos.getY() < 62 ? Blocks.STONE.defaultBlockState()
                : pos.getY() == 63 ? Blocks.SHORT_GRASS.defaultBlockState() : Blocks.AIR.defaultBlockState();
        var result = run(Direction.NORTH, groundBelow62,
                Operation.foundation("cobblestone", new Cell(0, -1, 0), new Cell(0, -1, 0)),
                Operation.replace("dirt", "stone", new Cell(1, -3, 0), new Cell(1, -3, 0)));
        assertEquals(Map.of(new BlockPos(10, 63, 10), Blocks.COBBLESTONE.defaultBlockState(),
                new BlockPos(10, 62, 10), Blocks.COBBLESTONE.defaultBlockState(),
                new BlockPos(11, 61, 10), Blocks.DIRT.defaultBlockState()), result.placements());
    }

    @Test
    void reportsInvalidPaletteBlocksAndSkipsOutsideWorld() throws Exception {
        var result = PlanPipeline.run(new BuildPlan("t", "", List.of(
                Operation.voxels(Map.of('a', "stone", 'b', "minecraft:not_real"), new Cell(0, 0, 0), List.of(List.of("ab"))),
                Operation.set("stone", new Cell(0, 10, 0)))), new BlockPos(0, 315, 0), Direction.NORTH, OVERWORLD, EMPTY, 100);
        assertEquals(1, result.placements().size());
        assertEquals(1, result.outsideWorld());
        assertTrue(result.invalid().contains("minecraft:not_real"));
    }
}
