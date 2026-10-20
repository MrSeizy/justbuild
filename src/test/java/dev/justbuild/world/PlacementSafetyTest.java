package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PlacementSafetyTest {
    /** Stone below y = 0, air above. */
    static final Function<BlockPos, BlockState> GROUND = pos -> pos.getY() < 0 ? Blocks.STONE.defaultBlockState()
            : Blocks.AIR.defaultBlockState();
    /** The build may affect x and z from -3 to 3. */
    static final Predicate<BlockPos> AREA = pos -> Math.abs(pos.getX()) <= 3 && Math.abs(pos.getZ()) <= 3;

    @BeforeAll
    static void bootstrap() {
        McBootstrap.init();
    }

    private static PlacementSafety.Result check(Map<BlockPos, BlockState> placements) {
        return PlacementSafety.check(placements, GROUND, AREA);
    }

    @Test
    void refusesBlocksThatActBeyondTheBuildOrCannotBeRemoved() {
        Map<BlockPos, BlockState> plan = new LinkedHashMap<>();
        plan.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        plan.put(new BlockPos(1, 0, 0), Blocks.PISTON.defaultBlockState());
        plan.put(new BlockPos(2, 0, 0), Blocks.REDSTONE_BLOCK.defaultBlockState());
        plan.put(new BlockPos(3, 0, 0), Blocks.OBSERVER.defaultBlockState());
        plan.put(new BlockPos(0, 0, 1), Blocks.COMMAND_BLOCK.defaultBlockState());
        plan.put(new BlockPos(1, 0, 1), Blocks.BARRIER.defaultBlockState());
        plan.put(new BlockPos(2, 0, 1), Blocks.BEDROCK.defaultBlockState());
        plan.put(new BlockPos(3, 0, 1), Blocks.HOPPER.defaultBlockState());
        plan.put(new BlockPos(0, 0, 2), Blocks.SPONGE.defaultBlockState());
        plan.put(new BlockPos(1, 0, 2), Blocks.SPAWNER.defaultBlockState());
        plan.put(new BlockPos(2, 0, 2), Blocks.REPEATER.defaultBlockState());
        plan.put(new BlockPos(3, 0, 2), Blocks.OAK_BUTTON.defaultBlockState());
        plan.put(new BlockPos(0, 0, 3), Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.POWERED, true));
        PlacementSafety.Result result = check(plan);
        assertEquals(Blocks.STONE.defaultBlockState(), result.placements().get(new BlockPos(0, 0, 0)));
        assertEquals(Blocks.OAK_BUTTON.defaultBlockState(), result.placements().get(new BlockPos(3, 0, 2)));
        assertEquals(Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.POWERED, false),
                result.placements().get(new BlockPos(0, 0, 3)), "switches are placed off");
        assertEquals(3, result.placements().size(), result.placements().toString());
        assertTrue(result.dropped().stream().anyMatch(s -> s.contains("piston")), result.dropped().toString());
    }

    @Test
    void pumpkinsThatWouldCompleteAGolemAreLeftOut() {
        Map<BlockPos, BlockState> plan = new LinkedHashMap<>();
        plan.put(new BlockPos(0, 0, 0), Blocks.SNOW_BLOCK.defaultBlockState());
        plan.put(new BlockPos(0, 1, 0), Blocks.SNOW_BLOCK.defaultBlockState());
        plan.put(new BlockPos(0, 2, 0), Blocks.CARVED_PUMPKIN.defaultBlockState());
        plan.put(new BlockPos(2, 0, 0), Blocks.STONE.defaultBlockState());
        plan.put(new BlockPos(2, 1, 0), Blocks.JACK_O_LANTERN.defaultBlockState());
        PlacementSafety.Result result = check(plan);
        assertFalse(result.placements().containsKey(new BlockPos(0, 2, 0)));
        assertTrue(result.placements().containsKey(new BlockPos(2, 1, 0)));
    }

    @Test
    void fallingBlocksNeedSomethingUnderThem() {
        Map<BlockPos, BlockState> plan = new LinkedHashMap<>();
        plan.put(new BlockPos(0, 5, 0), Blocks.SAND.defaultBlockState());
        plan.put(new BlockPos(0, 6, 0), Blocks.GRAVEL.defaultBlockState());
        plan.put(new BlockPos(1, 4, 0), Blocks.STONE.defaultBlockState());
        plan.put(new BlockPos(1, 5, 0), Blocks.SAND.defaultBlockState());
        plan.put(new BlockPos(2, 0, 0), Blocks.GRAVEL.defaultBlockState());
        PlacementSafety.Result result = check(plan);
        assertFalse(result.placements().containsKey(new BlockPos(0, 5, 0)), "sand over air would fall");
        assertFalse(result.placements().containsKey(new BlockPos(0, 6, 0)), "and so would the gravel on it");
        assertTrue(result.placements().containsKey(new BlockPos(1, 5, 0)));
        assertTrue(result.placements().containsKey(new BlockPos(2, 0, 0)), "resting on the ground");
    }

    @Test
    void waterStaysOnlyWhereItCannotFlowOutOfTheBuild() {
        Map<BlockPos, BlockState> plan = new LinkedHashMap<>();
        // A basin: water at (0,0,0) walled in on four sides, ground below.
        plan.put(new BlockPos(0, 0, 0), Blocks.WATER.defaultBlockState());
        for (BlockPos wall : new BlockPos[] {new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0), new BlockPos(0, 0, 1),
                new BlockPos(0, 0, -1)}) {
            plan.put(wall, Blocks.STONE.defaultBlockState());
        }
        // An open spout high up: it falls to the ground and spreads past x = 3.
        plan.put(new BlockPos(-2, 8, -2), Blocks.WATER.defaultBlockState());
        PlacementSafety.Result result = check(plan);
        assertTrue(result.placements().containsKey(new BlockPos(0, 0, 0)), "the basin keeps its water");
        assertFalse(result.placements().containsKey(new BlockPos(-2, 8, -2)), "the spout would flood outside the area");
    }

    @Test
    void flowingWaterCannotBypassContainment() {
        for (int level = 1; level <= 15; level++) {
            BlockPos spout = new BlockPos(-2, 8, -2);
            var state = Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, level);
            assertFalse(check(Map.of(spout, state)).placements().containsKey(spout), "flowing water level " + level);
        }
    }

    @Test
    void meltableIceCannotIntroduceWaterAfterValidation() {
        var result = check(Map.of(BlockPos.ZERO, Blocks.ICE.defaultBlockState(), BlockPos.ZERO.east(),
                Blocks.FROSTED_ICE.defaultBlockState(), BlockPos.ZERO.west(), Blocks.PACKED_ICE.defaultBlockState()));
        assertEquals(Map.of(BlockPos.ZERO.west(), Blocks.PACKED_ICE.defaultBlockState()), result.placements());
    }
}
