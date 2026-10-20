package dev.justbuild.client;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

final class FloorPreviewScreen extends ToolScreen {
    private record Column(int x, int z) { }
    private Map<Column, Map.Entry<BlockPos, BlockState>> surface = Map.of();
    private int minX, minZ, spanX, spanZ;
    FloorPreviewScreen(Screen parent) { super("Compare before / after", parent); }
    @Override protected void build() {
        int quarter = (panelWidth - 12) / 4;
        button("Lower layer", left, 36, quarter, () -> { FloorPreview.lower(); rebuildWidgets(); }).active = !FloorPreview.blocks().isEmpty();
        button("Upper layer", left + quarter + 4, 36, quarter, () -> { FloorPreview.upper(); rebuildWidgets(); }).active = !FloorPreview.blocks().isEmpty();
        button("All layers", left + 2 * (quarter + 4), 36, quarter, () -> { FloorPreview.all(); rebuildWidgets(); });
        button("View in world", left + 3 * (quarter + 4), 36, quarter, () -> minecraft.setScreen(null));
        int third = (panelWidth - 8) / 3;
        button("Before", left, 62, third, () -> { GhostPreview.before(true); rebuildWidgets(); });
        button("After", left + third + 4, 62, third, () -> { GhostPreview.before(false); rebuildWidgets(); });
        button("Filter: " + GhostPreview.filter().name().toLowerCase(java.util.Locale.ROOT), left + 2 * (third + 4), 62, third,
                () -> { var filters = GhostPreview.Filter.values(); GhostPreview.filter(filters[(GhostPreview.filter().ordinal() + 1) % filters.length]); rebuildWidgets(); });
        button("Back", left, height - 28, panelWidth, this::onClose);
        Map<Column, Map.Entry<BlockPos, BlockState>> top = new HashMap<>();
        FloorPreview.blocks().entrySet().stream().filter(e -> e.getKey().getY() <= FloorPreview.ceiling() && GhostPreview.accepts(e.getKey())).forEach(e -> {
            Column column = new Column(e.getKey().getX(), e.getKey().getZ()); var old = top.get(column);
            if (old == null || old.getKey().getY() < e.getKey().getY()) top.put(column, e);
        }); surface = Map.copyOf(top);
        minX = FloorPreview.extent().stream().mapToInt(BlockPos::getX).min().orElse(0);
        minZ = FloorPreview.extent().stream().mapToInt(BlockPos::getZ).min().orElse(0);
        spanX = FloorPreview.extent().stream().mapToInt(BlockPos::getX).max().orElse(0) - minX + 1;
        spanZ = FloorPreview.extent().stream().mapToInt(BlockPos::getZ).max().orElse(0) - minZ + 1;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float tick) {
        super.extractRenderState(g, mouseX, mouseY, tick);
        String title = FloorPreview.blocks().isEmpty() ? "No blocks in this view." : (GhostPreview.before() ? "Before" : "After") + " · north ↑ · "
                + (FloorPreview.ceiling() == Integer.MAX_VALUE ? "all layers" : "showing through Y " + FloorPreview.ceiling())
                + (FloorPreview.total() > FloorPreview.blocks().size() ? " · partial model" : "");
        g.text(font, font.plainSubstrByWidth(title, panelWidth), left, 88, 0xFFEEEEEE);
        int available = Math.max(12, height - 158); double scale = Math.min((double) panelWidth / spanX, (double) available / spanZ);
        int x0 = left + (panelWidth - (int) (spanX * scale)) / 2, y0 = 104;
        g.fill(x0 - 1, y0 - 1, x0 + (int) (spanX * scale) + 1, y0 + (int) (spanZ * scale) + 1, 0xFF20252B);
        for (var e : surface.entrySet()) {
            int x = x0 + (int) ((e.getKey().x() - minX) * scale), z = y0 + (int) ((e.getKey().z() - minZ) * scale);
            int x2 = x0 + Math.max(1, (int) ((e.getKey().x() - minX + 1) * scale)), z2 = y0 + Math.max(1, (int) ((e.getKey().z() - minZ + 1) * scale));
            var pos = e.getValue().getKey(); var state = e.getValue().getValue();
            int rgb = state.getMapColor(EmptyBlockGetter.INSTANCE, pos).col; if (rgb == 0) rgb = 0x888888;
            int cutoff = FloorPreview.ceiling() == Integer.MAX_VALUE ? FloorPreview.maxY() : FloorPreview.ceiling();
            double light = Math.max(0.48, 1.0 - Math.min(6, cutoff - pos.getY()) * 0.12);
            int shaded = (int) ((rgb >> 16 & 255) * light) << 16 | (int) ((rgb >> 8 & 255) * light) << 8 | (int) ((rgb & 255) * light);
            g.fill(x, z, Math.max(x + 1, x2 - (scale >= 5 ? 1 : 0)), Math.max(z + 1, z2 - (scale >= 5 ? 1 : 0)), 0xFF000000 | shaded);
            if (scale >= 18 && !state.getBlock().asItem().getDefaultInstance().isEmpty()) g.item(state.getBlock().asItem().getDefaultInstance(), x + 1, z + 1);
            if (mouseX >= x && mouseX < x2 && mouseY >= z && mouseY < z2) g.setTooltipForNextFrame(font,
                    java.util.List.of(state.getBlock().getName(), net.minecraft.network.chat.Component.literal(pos.toShortString())), java.util.Optional.empty(), mouseX, mouseY);
        }
    }
}
