package dev.justbuild.client;

import dev.justbuild.net.GhostPreviewPayload;
import dev.justbuild.world.BlockResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Separate model: slicing it never alters the real client or server world. */
final class FloorPreview {
    private static Map<BlockPos, BlockState> blocks = Map.of(), before = Map.of();
    private static int beforeTotal;
    private static int minY, maxY, ceiling = Integer.MAX_VALUE, total;
    private FloorPreview() { }
    /** Kept even when the player is in another dimension, so the section view still works after they return. */
    static void receive(GhostPreviewPayload payload) {
        if (Minecraft.getInstance().level == null) return;
        var palette = payload.palette().stream().map(s -> BlockResolver.parse(s).orElse(Blocks.AIR.defaultBlockState())).toList();
        Map<BlockPos, BlockState> model = new LinkedHashMap<>();
        for (var cell : payload.cells()) if (!palette.get(cell.state()).isAir()) model.put(cell.pos(), palette.get(cell.state()));
        blocks = dev.justbuild.world.PositionMaps.frozen(model); total = payload.total();
        minY = blocks.keySet().stream().mapToInt(BlockPos::getY).min().orElse(0);
        maxY = blocks.keySet().stream().mapToInt(BlockPos::getY).max().orElse(0);
        ceiling = Integer.MAX_VALUE;
    }
    static void receive(dev.justbuild.net.SectionPreviewPayload payload) {
        receive(payload.before()); before = blocks; beforeTotal = total;
        receive(payload.model());
        minY = Math.min(minY, before.keySet().stream().mapToInt(BlockPos::getY).min().orElse(minY));
        maxY = Math.max(maxY, before.keySet().stream().mapToInt(BlockPos::getY).max().orElse(maxY));
    }
    static void clear() { blocks = Map.of(); before = Map.of(); beforeTotal = 0; ceiling = Integer.MAX_VALUE; total = 0; }
    static Map<BlockPos, BlockState> blocks() { return GhostPreview.before() ? before : blocks; }
    static java.util.Set<BlockPos> extent() { var positions = new java.util.HashSet<>(blocks.keySet()); positions.addAll(before.keySet()); return positions; }
    static int minY() { return minY; }
    static int maxY() { return maxY; }
    static int ceiling() { return ceiling; }
    static int total() { return GhostPreview.before() ? beforeTotal : total; }
    static void all() { ceiling = Integer.MAX_VALUE; }
    static void lower() { ceiling = Math.max(minY, (ceiling == Integer.MAX_VALUE ? maxY : ceiling) - 1); }
    static void upper() { ceiling = Math.min(maxY, ceiling == Integer.MAX_VALUE ? maxY : ceiling + 1); }
}
