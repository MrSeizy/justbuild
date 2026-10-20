package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class NamedPartsScreen extends ToolScreen {
    private String name = "";
    private int page;
    NamedPartsScreen(Screen parent) { super("Named building parts", parent); }
    @Override protected void build() {
        var field = new GlassEditBox(font, left, 36, panelWidth - 100, 20, Component.literal("Part name"));
        field.setMaxLength(60); field.setValue(name); field.setHint(Component.literal("Kitchen, East tower, Roof…"));
        field.setResponder(value -> name = value); addRenderableWidget(field);
        button("Save part", left + panelWidth - 96, 36, 96, () -> { if (!name.isBlank()) run("box save " + name.strip()); })
                .active = ClientState.part() != null && ClientState.string("selectedBuild", null) != null;
        var parts = ClientState.namedParts().keySet().stream().toList();
        int rows = Math.max(1, (height - 148) / 24), pages = Math.max(1, (parts.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < rows && page * rows + i < parts.size(); i++) {
            String part = parts.get(page * rows + i);
            button("Select: " + part, left, 82 + i * 24, panelWidth - 76, () -> run("box select " + part));
            button("Delete", left + panelWidth - 72, 82 + i * 24, 72, () -> run("box delete " + part)).danger();
        }
        int third = (panelWidth - 8) / 3;
        button("Back", left, height - 28, third, this::onClose);
        button("Previous", left + third + 4, height - 28, third, () -> { page--; rebuildWidgets(); }).active = page > 0;
        button("Next", left + 2 * (third + 4), height - 28, third, () -> { page++; rebuildWidgets(); }).active = page < pages - 1;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        g.text(font, font.plainSubstrByWidth(ClientState.string("selectedTitle", "Select a building first."), panelWidth), left, 66, Glass.MUTED);
        if (ClientState.namedParts().isEmpty()) text(g, "Select a part with the wand, then save its name. Select a saved part before asking for an edit.", 88, height - 48, Glass.MUTED);
    }
}
