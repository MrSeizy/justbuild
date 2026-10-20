package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DiffTest {
    @BeforeAll
    static void bootstrap() {
        McBootstrap.init();
    }

    static final BlockPos A = new BlockPos(0, 0, 0);
    static final BlockPos B = new BlockPos(1, 0, 0);
    static final BlockPos C = new BlockPos(2, 0, 0);
    static final BlockPos D = new BlockPos(3, 0, 0);

    static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }

    @Test
    void changedAddedAndRemovedPositions() {
        Map<BlockPos, BlockState> from = new LinkedHashMap<>();
        from.put(A, stone());
        from.put(B, stone());
        from.put(D, stone());
        Map<BlockPos, BlockState> to = new LinkedHashMap<>();
        to.put(A, stone());
        to.put(B, Blocks.GOLD_BLOCK.defaultBlockState());
        to.put(C, Blocks.GLASS.defaultBlockState());
        Map<BlockPos, BlockState> baseline = Map.of(D, Blocks.GRASS_BLOCK.defaultBlockState());

        Map<BlockPos, BlockState> diff = Diff.between(from, to, baseline);
        assertEquals(Map.of(B, Blocks.GOLD_BLOCK.defaultBlockState(), C, Blocks.GLASS.defaultBlockState(),
                D, Blocks.GRASS_BLOCK.defaultBlockState()), diff);
    }

    @Test
    void removalWithoutBaselineIsSkipped() {
        assertTrue(Diff.between(Map.of(A, stone()), Map.of(), Map.of()).isEmpty());
    }

    @Test
    void newBuildAndFullUndo() {
        Map<BlockPos, BlockState> build = Map.of(A, stone(), B, stone());
        assertEquals(build, Diff.between(Map.of(), build, Map.of()));
        Map<BlockPos, BlockState> baseline = Map.of(A, Blocks.AIR.defaultBlockState(), B, Blocks.DIRT.defaultBlockState());
        assertEquals(baseline, Diff.between(build, Map.of(), baseline));
    }

    @Test
    void undoChangesOnlyWhatTheBuildCovered() {
        McBootstrap.init();
        BlockPos wall = new BlockPos(0, 0, 0), dugOut = new BlockPos(0, -3, 0);
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> from = java.util.Map.of(wall,
                net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
        // The earlier version recomputed today reaches a cell the build never covered (ground dug out since).
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> to = java.util.Map.of(
                wall, net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState(),
                dugOut, net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState());
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> baseline = java.util.Map.of(wall,
                net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
        var undo = Diff.forUndo(from, to, baseline);
        assertEquals(java.util.Set.of(wall), undo.keySet());
    }
}
