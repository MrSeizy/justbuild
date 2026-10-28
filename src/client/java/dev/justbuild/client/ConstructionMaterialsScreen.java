package dev.justbuild.client;

import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ConstructionMaterialsScreen extends ConstructionMenuScreen {
    private static final int TOP = 78, ROW_HEIGHT = 18;
    private int page;
    private String search = "";
    private boolean missingOnly;
    private List<ConstructionClient.Material> visible = List.of();
    private GlassEditBox searchBox;
    private GlassButton previous, next;
    ConstructionMaterialsScreen(Screen parent) { super("Whole build · remaining materials", parent); }
    private int rows() { return Math.max(1, (height - 90 - TOP) / ROW_HEIGHT); }
    private int pages() { return Math.max(1, (visible.size() + rows() - 1) / rows()); }
    List<ConstructionClient.Material> displayedMaterials() { return visible; }
    @Override protected void build() {
        searchBox = new GlassEditBox(font, left, 34, panelWidth - 112, 20, Component.literal("Search materials"));
        searchBox.setMaxLength(80); searchBox.setValue(search); searchBox.setHint(Component.literal("Search materials…"));
        searchBox.setResponder(value -> { search = value; page = 0; refresh(); });
        addRenderableWidget(searchBox);
        button(missingOnly ? "Show: missing" : "Show: all", left + panelWidth - 108, 34, 108, () -> {
            missingOnly = !missingOnly; page = 0; rebuildWidgets();
        }).choice().setTooltip(Tooltip.create(Component.literal("Missing compares the whole build's needs with the linked chest in Cobble mode, or your inventory in Guide and Assist.")));
        int third = (panelWidth - 8) / 3;
        previous = button("Previous", left, height - 66, third, () -> { page--; refresh(); });
        next = button("Next", left + 2 * (third + 4), height - 66, third, () -> { page++; refresh(); });
        button("Back", left, height - 24, panelWidth, this::onClose);
        refresh();
    }
    private void refresh() {
        var s = ConstructionClient.state();
        String needle = search.strip().toLowerCase(Locale.ROOT);
        visible = s == null ? List.of() : s.materials().stream()
                .filter(m -> m.name().toLowerCase(Locale.ROOT).contains(needle) || m.item().toLowerCase(Locale.ROOT).contains(needle))
                .filter(m -> !missingOnly || (s.mode().equals("COBBLE") ? m.chest() : m.inventory()) < m.need()).toList();
        page = Math.clamp(page, 0, pages() - 1);
        if (previous != null) previous.active = page > 0;
        if (next != null) next.active = page + 1 < pages();
    }
    @Override void stateChanged() { refresh(); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x >= left && x < left + panelWidth && y >= TOP && y < height - 90 && vertical != 0) {
            page += vertical > 0 ? -1 : 1; refresh(); return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        super.extractRenderState(g, mx, my, dt); var s = ConstructionClient.state(); if (s == null) return;
        ConstructionMaterialList.draw(g, font, s, visible, page * rows(), rows(), left, 64, TOP, ROW_HEIGHT, panelWidth, mx, my);
        if (visible.isEmpty() && !s.materials().isEmpty())
            g.text(font, search.isBlank() && missingOnly ? "All remaining materials are supplied." : "No materials match these filters.", left + 4, TOP + 3, Glass.TEXT);
        String note = s.manual() > 0 ? s.manual() + " tasks need manual preparation." : s.mode().equals("COBBLE")
                ? "Supply counts come from the linked chest." : "Supply counts come from your inventory.";
        text(g, note, height - 86, height - 70, Glass.MUTED);
        g.centeredText(font, (page + 1) + " / " + pages(), width / 2, height - 60, Glass.MUTED);
    }
}
