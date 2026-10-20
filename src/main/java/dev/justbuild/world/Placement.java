package dev.justbuild.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public record Placement(BlockPos pos, BlockState state) {
}
