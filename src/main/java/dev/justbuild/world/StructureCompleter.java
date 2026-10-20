package dev.justbuild.world;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Adds the missing upper half of doors and tall plants and the missing head of beds. */
public final class StructureCompleter {
    private StructureCompleter() {
    }

    /** Mutates {@code placements}; returns how many blocks were added. */
    public static int complete(Map<BlockPos, BlockState> placements, LevelHeightAccessor height) {
        Map<BlockPos, BlockState> additions = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : placements.entrySet()) {
            BlockState state = entry.getValue();
            BlockPos partnerPos = null;
            BlockState partner = null;
            if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
                partnerPos = entry.getKey().above();
                partner = state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
            } else if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.FOOT) {
                partnerPos = entry.getKey().relative(BedBlock.getConnectedDirection(state));
                partner = state.setValue(BedBlock.PART, BedPart.HEAD);
            }
            if (partnerPos != null && !placements.containsKey(partnerPos) && !additions.containsKey(partnerPos)
                    && !height.isOutsideBuildHeight(partnerPos)) {
                additions.put(partnerPos, partner);
            }
        }
        placements.putAll(additions);
        return additions.size();
    }
}
