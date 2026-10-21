package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared sizing and command feedback for the small builder tools. */
abstract class ToolScreen extends FormScreen {
    protected final Screen parent;
    private final long opened = ClientState.lineCount();
    protected int left, panelWidth;
    ToolScreen(String title, Screen parent) { super(Component.literal(title)); this.parent = parent; }
    @Override public boolean isPauseScreen() { return false; }
    @Override protected final void init() {
        panelWidth = Math.min(440, width - 20); left = (width - panelWidth) / 2; build();
    }
    protected abstract void build();
    protected GlassButton button(String label, int x, int y, int width, Runnable action) {
        return addRenderableWidget(GlassButton.builder(Component.literal(label), b -> action.run()).bounds(x, y, width, 20).build());
    }
    protected void run(String command) {
        if (minecraft.player != null) minecraft.player.connection.sendCommand("justbuild " + command);
    }
    void stateChanged() { rebuildWidgets(); }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    /** The glass sheet instead of the vanilla blur: the world stays visible around the panel. */
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Glass.sheet(graphics, left, panelWidth, height);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, font.plainSubstrByWidth(getTitle().getString(), panelWidth), width / 2, 12, Glass.TITLE);
        StatusLine.draw(graphics, font, opened, left, height - 38, panelWidth, mouseX, mouseY);
    }
    protected int text(GuiGraphicsExtractor graphics, String text, int y, int bottom, int color) {
        bottom = Math.min(bottom, height - 52); // Leave both command-feedback lines above Back clear.
        for (var line : font.split(Component.literal(text), panelWidth)) {
            if (y + font.lineHeight > bottom) break;
            graphics.text(font, line, left, y, color); y += font.lineHeight + 2;
        }
        return y;
    }
}
