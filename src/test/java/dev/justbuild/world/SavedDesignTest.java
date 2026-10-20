package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.Operation;
import dev.justbuild.plan.PlanException;
import dev.justbuild.plan.PlanMaterials;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SavedDesignTest {
    @TempDir Path dir;
    @BeforeAll static void bootstrap() { McBootstrap.init(); }

    @Test void designSurvivesStorageAndRotatesSubstitutedStates() throws Exception {
        BuildPlan plan = new BuildPlan("Cottage", "", List.of(Operation.set("oak_stairs[facing=north]", new Cell(2, 0, 0)),
                Operation.voxels(Map.of('A', "oak_planks"), new Cell(0, 0, 0), List.of(List.of("A")))));
        DesignStore store = new DesignStore(dir);
        var design = new DesignStore.Design("abcdef12", "owner", "Reusable cottage", plan, 1000);
        store.save(design);
        assertEquals(List.of(design), store.loadAll());
        BuildPlan copy = PlanMaterials.apply(store.loadAll().getFirst().plan(), "Copy",
                PlanMaterials.parse("oak_planks=spruce_planks,oak_stairs=spruce_stairs"));
        var placed = PlanPipeline.run(copy, new BlockPos(10, 70, 10), Direction.EAST,
                LevelHeightAccessor.create(-64, 384), p -> Blocks.AIR.defaultBlockState(), 100).placements();
        assertTrue(placed.get(new BlockPos(10, 70, 10)).is(Blocks.SPRUCE_PLANKS));
        var stairs = placed.get(new BlockPos(10, 70, 12));
        assertTrue(stairs.is(Blocks.SPRUCE_STAIRS));
        assertEquals(Direction.EAST, stairs.getValue(StairBlock.FACING));
        assertEquals("oak_stairs[facing=north]", plan.operations().getFirst().block(), "copy modified the saved source");
    }

    @Test void swapsRoofDetailsAndRejectsIncompatibleProperties() throws Exception {
        Operation roof = Operation.roof("oak_planks", null, new Cell(0, 0, 0), new Cell(4, 0, 4), "gable", "x",
                "oak_planks", 1, true, true, false, List.of("oak_planks"), "oak_log", List.of("oak_planks"));
        BuildPlan plan = new BuildPlan("Roof", "", List.of(roof));
        BuildPlan copy = PlanMaterials.apply(plan, "Spruce roof", PlanMaterials.parse("oak_planks=spruce_planks,oak_log=spruce_log"));
        var changed = copy.operations().getFirst();
        assertEquals("minecraft:spruce_planks", changed.block());
        assertEquals("minecraft:spruce_planks", changed.edge());
        assertEquals("minecraft:spruce_log", changed.beam());
        assertEquals(List.of("minecraft:spruce_planks"), changed.mix());
        assertEquals(List.of("minecraft:spruce_planks"), changed.finial());
        assertThrows(PlanException.class, () -> PlanMaterials.parse("oak_planks=does_not_exist"));
        assertThrows(PlanException.class, () -> PlanMaterials.parse("oak_planks=stone,oak_planks=gold_block"));
        BuildPlan stairs = new BuildPlan("Stairs", "", List.of(Operation.set("oak_stairs[facing=north]", new Cell(0, 0, 0))));
        assertThrows(PlanException.class, () -> PlanMaterials.apply(stairs, "Bad", PlanMaterials.parse("oak_stairs=stone")));
        assertThrows(IllegalArgumentException.class, () -> new DesignStore.Design("../../bad", "owner", "Bad", plan, 0));
    }

    @Test void capturesActualStatesAndAirWithoutTouchingUnmappedCells() throws Exception {
        BlockPos origin = new BlockPos(10, 70, 10);
        var stair = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> actual = Map.of(
                origin.offset(1, 1, 2), stair, origin.offset(1, 1, 3), Blocks.DIAMOND_BLOCK.defaultBlockState(),
                origin.offset(1, 1, 5), Blocks.AIR.defaultBlockState());
        BuildPlan snapshot = PlanSnapshot.capture("Finished", origin, Direction.EAST, actual);
        var roundTrip = PlanPipeline.run(snapshot, origin, Direction.EAST, LevelHeightAccessor.create(-64, 384),
                p -> Blocks.STONE.defaultBlockState(), 100).placements();
        assertEquals(actual, roundTrip);
        assertFalse(roundTrip.containsKey(origin.offset(1, 1, 4)), "a gap in known positions must be left alone");
        var copy = PlanPipeline.run(snapshot, BlockPos.ZERO, Direction.NORTH, LevelHeightAccessor.create(-64, 384),
                p -> Blocks.STONE.defaultBlockState(), 100).placements();
        assertTrue(copy.get(new BlockPos(3, 1, -1)).is(Blocks.DIAMOND_BLOCK));
        assertEquals(Direction.NORTH, copy.get(new BlockPos(2, 1, -1)).getValue(StairBlock.FACING));
    }
}
