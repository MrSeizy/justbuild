package dev.justbuild.world;

import dev.justbuild.net.GhostPreviewPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Describes actual state changes, distinguishing additions, replacements, and removals. */
public final class ChangePreview {
    private ChangePreview() { }

    /** Full-plan counts, independent of the number of ghost cells a client can receive. */
    public record Counts(int added, int replaced, int removed) { }

    public static Counts counts(Map<BlockPos, BlockState> placements, Function<BlockPos, BlockState> current) {
        int added = 0, replaced = 0, removed = 0;
        for (var entry : placements.entrySet()) {
            BlockState old = current.apply(entry.getKey()), next = entry.getValue();
            if (old.equals(next)) continue;
            if (next.isAir()) removed++;
            else if (old.isAir()) added++;
            else replaced++;
        }
        return new Counts(added, replaced, removed);
    }

    public static GhostPreviewPayload create(String dimension, Map<BlockPos, BlockState> placements,
            Function<BlockPos, BlockState> current) {
        return create(dimension, placements, current, GhostPreviewPayload.MAX_CELLS);
    }

    /** At most {@code maxCells} cells; {@code total} still counts every change, so the player can be told. */
    public static GhostPreviewPayload create(String dimension, Map<BlockPos, BlockState> placements,
            Function<BlockPos, BlockState> current, int maxCells) {
        Map<String, Integer> palette = new LinkedHashMap<>();
        List<GhostPreviewPayload.Cell> cells = new ArrayList<>();
        int total = 0;
        for (var entry : placements.entrySet()) {
            BlockState old = current.apply(entry.getKey()), next = entry.getValue();
            if (old.equals(next)) continue;
            total++;
            if (cells.size() >= maxCells) continue;
            String state = BlockStateParser.serialize(next.isAir() ? old : next);
            Integer index = palette.get(state);
            if (index == null) {
                if (palette.size() >= GhostPreviewPayload.MAX_PALETTE || state.length() > 256) continue;
                index = palette.size();
                palette.put(state, index);
            }
            cells.add(new GhostPreviewPayload.Cell(entry.getKey().immutable(), index,
                    next.isAir() ? GhostPreviewPayload.REMOVE : old.isAir() ? GhostPreviewPayload.ADD : GhostPreviewPayload.REPLACE));
        }
        return new GhostPreviewPayload(dimension, List.copyOf(palette.keySet()), cells, total);
    }
}
