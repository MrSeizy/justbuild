package dev.justbuild.world;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** The one rule for applying versions: what to place to turn the world from showing {@code from} into {@code to}. */
public final class Diff {
    private Diff() {
    }

    /**
     * Positions in {@code to} whose state differs from {@code from} get {@code to}'s state; positions only in
     * {@code from} go back to {@code baseline} (skipped when the baseline has no entry).
     */
    public static Map<BlockPos, BlockState> between(Map<BlockPos, BlockState> from, Map<BlockPos, BlockState> to,
            Map<BlockPos, BlockState> baseline) {
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : to.entrySet()) {
            if (from.get(entry.getKey()) != entry.getValue()) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        for (BlockPos pos : from.keySet()) {
            if (!to.containsKey(pos)) {
                BlockState original = baseline.get(pos);
                if (original != null) {
                    out.put(pos, original);
                }
            }
        }
        return out;
    }

    /**
     * Undo's diff: like {@link #between}, but only where the build placed something ({@code baseline}) or shows a block
     * now ({@code from}). An earlier version is recomputed against today's world, so a foundation or replace in it can
     * reach cells the build never covered (ground dug out since); undo must not build there.
     */
    public static Map<BlockPos, BlockState> forUndo(Map<BlockPos, BlockState> from, Map<BlockPos, BlockState> to,
            Map<BlockPos, BlockState> baseline) {
        Map<BlockPos, BlockState> out = between(from, to, baseline);
        out.keySet().removeIf(pos -> !baseline.containsKey(pos) && !from.containsKey(pos));
        return out;
    }
}
