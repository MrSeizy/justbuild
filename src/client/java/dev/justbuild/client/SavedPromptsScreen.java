package dev.justbuild.client;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class SavedPromptsScreen extends ToolScreen {
    private final Consumer<String> use;
    private int page;
    private String message = "Choose a prompt to put in the editor.";
    SavedPromptsScreen(Screen parent, Consumer<String> use) { super("Reusable prompts", parent); this.use = use; }
    @Override protected void build() {
        List<String> values;
        try { values = SavedPrompts.load(); } catch (java.io.IOException e) { values = List.of(); message = e.getMessage(); }
        int rows = Math.max(1, (height - 132) / 24), pages = Math.max(1, (values.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < rows && page * rows + i < values.size(); i++) {
            String text = values.get(page * rows + i); int y = 56 + i * 24;
            button(text.lines().findFirst().orElse(text), left, y, panelWidth - 66, () -> { use.accept(text); onClose(); })
                    .setTooltip(Tooltip.create(Component.literal(text)));
            button("Remove", left + panelWidth - 62, y, 62, () -> {
                try { SavedPrompts.remove(text); } catch (java.io.IOException e) { message = e.getMessage(); }
                rebuildWidgets();
            });
        }
        button("Previous", left, height - 76, 82, () -> { page--; rebuildWidgets(); }).active = page > 0;
        button("Next", left + panelWidth - 82, height - 76, 82, () -> { page++; rebuildWidgets(); }).active = page + 1 < pages;
        button("Back", left, height - 28, panelWidth, this::onClose);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick); g.text(font, font.plainSubstrByWidth(message, panelWidth), left, 36, Glass.MUTED);
    }
}
