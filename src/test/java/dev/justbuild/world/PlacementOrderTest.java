package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PlacementOrderTest {
    @BeforeAll
    static void bootstrap() {
        McBootstrap.init();
    }

    @Test
    void structuralBlocksFirstThenAttachedEachBottomUp() {
        Map<BlockPos, BlockState> map = new LinkedHashMap<>();
        map.put(new BlockPos(0, 3, 0), Blocks.TORCH.defaultBlockState());
        map.put(new BlockPos(0, 2, 0), Blocks.STONE.defaultBlockState());
        map.put(new BlockPos(1, 1, 0), Blocks.WATER.defaultBlockState());
        map.put(new BlockPos(0, 1, 0), Blocks.AIR.defaultBlockState());
        map.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        List<BlockPos> order = PlacementOrder.order(map).stream().map(Placement::pos).toList();
        assertEquals(List.of(new BlockPos(0, 0, 0), new BlockPos(0, 1, 0), new BlockPos(0, 2, 0),
                new BlockPos(1, 1, 0), new BlockPos(0, 3, 0)), order);
    }
}
