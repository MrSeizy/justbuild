package dev.justbuild.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

final class WalkabilityScreen extends ToolScreen {
    private int page, rows, pages;
    private List<FormattedCharSequence> lines = List.of();
    WalkabilityScreen(Screen parent) { super("Walkability checks", parent); }
    @Override protected void build() {
        int third = (panelWidth - 8) / 3;
        button("Check again", left, 36, third, () -> { page = 0; run("walkability"); });
        button("View markers", left + third + 4, 36, third, () -> minecraft.gui.setScreen(null));
        button("Clear markers", left + 2 * (third + 4), 36, third, () -> run("walkability clear"));
        List<FormattedCharSequence> wrapped = new ArrayList<>(); var route = ClientState.route();
        for (String line : route == null ? List.of("Select a building or preview, then check its routes. Markers expire after two minutes.") : route.lines()) {
            wrapped.addAll(font.split(Component.literal(line), panelWidth)); wrapped.add(Component.empty().getVisualOrderText());
        }
        lines = List.copyOf(wrapped); rows = Math.max(1, (height - 118) / (font.lineHeight + 2));
        pages = Math.max(1, (lines.size() + rows - 1) / rows); page = Math.min(page, pages - 1);
        button("Back", left, height - 28, third, this::onClose);
        button("Previous", left + third + 4, height - 28, third, () -> { page--; rebuildWidgets(); }).active = page > 0;
        button("Next", left + 2 * (third + 4), height - 28, third, () -> { page++; rebuildWidgets(); }).active = page < pages - 1;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        for (int i = 0; i < rows && page * rows + i < lines.size(); i++) g.text(font, lines.get(page * rows + i), left, 66 + i * (font.lineHeight + 2), 0xFFEEEEEE);
    }
}
