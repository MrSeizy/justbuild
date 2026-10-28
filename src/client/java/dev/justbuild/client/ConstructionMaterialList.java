package dev.justbuild.client;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Shared whole-project table for the project panel and its expanded materials view. */
final class ConstructionMaterialList {
    private ConstructionMaterialList() { }
    static void draw(GuiGraphicsExtractor g, Font font, ConstructionClient.State s, List<ConstructionClient.Material> materials, int first, int rows,
            int left, int header, int top, int rowHeight, int width, int mx, int my) {
        int need = left + width - 110, you = left + width - 58, chest = left + width - 6;
        boolean cobble = s.cobble(), icons = rowHeight >= 18, linked = s.chest() != null;
        g.fill(left, header - 3, left + width, top - 2, 0xFF293446);
        g.text(font, "Material", left + 4, header, Glass.TEXT);
        right(g, font, "Need", need, header, Glass.TEXT);
        right(g, font, "You", you, header, cobble ? Glass.MUTED : Glass.TITLE);
        right(g, font, "Chest", chest, header, cobble ? Glass.TITLE : Glass.MUTED);
        for (int row = 0; row < rows && first + row < materials.size(); row++) {
            var m = materials.get(first + row); int rowY = top + row * rowHeight, y = rowY + (rowHeight - font.lineHeight) / 2;
            boolean hover = mx >= left && mx < left + width && my >= rowY && my < rowY + rowHeight;
            g.fill(left, rowY, left + width, rowY + rowHeight, hover ? 0xFF344154 : row % 2 == 0 ? 0xFF1E2735 : 0xFF18212D);
            if (icons) g.item(m.icon(), left + 3, rowY + 1);
            int nameX = left + (icons ? 23 : 4), missing = Math.max(0, m.need() - s.supply(m));
            boolean unknown = cobble && !linked;
            // With nothing on hand the Need column already says how many are missing.
            String shortBy = missing == 0 || unknown || s.supply(m) == 0 ? "" : missing + " short";
            if (!shortBy.isEmpty()) right(g, font, shortBy, need - 40, y, ConstructionClient.SHORT);
            int nameWidth = need - 40 - nameX - (shortBy.isEmpty() ? 0 : font.width(shortBy) + 8);
            g.text(font, font.plainSubstrByWidth(m.name(), nameWidth), nameX, y, Glass.TEXT);
            right(g, font, Integer.toString(m.need()), need, y, Glass.TEXT);
            right(g, font, Integer.toString(m.inventory()), you, y, cobble ? Glass.MUTED : m.inventory() >= m.need() ? ConstructionClient.READY : ConstructionClient.SHORT);
            // No linked chest means no count at all, not zero supplies.
            right(g, font, linked ? Integer.toString(m.chest()) : "—", chest, y, !cobble || !linked ? Glass.MUTED
                    : m.chest() >= m.need() ? ConstructionClient.READY : ConstructionClient.SHORT);
            if (hover) {
                if (unknown) { g.setTooltipForNextFrame(font, Component.literal(m.name() + " · link a chest to count supplies"), mx, my); continue; }
                g.setTooltipForNextFrame(font, Component.literal(m.name() + " · " + (missing == 0 ? "Ready" : missing + " missing")
                        + (cobble ? " in linked chest" : " in inventory")), mx, my);
            }
        }
        if (s.materials().isEmpty()) g.text(font, s.manual() == 0 ? "All materials placed." : "Remaining work needs manual preparation.", left + 4, top + 3, Glass.TEXT);
    }
    private static void right(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        g.text(font, text, x - font.width(text), y, color);
    }
}
