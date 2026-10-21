package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

final class MaterialsScreen extends ToolScreen {
    private String from, to;
    MaterialsScreen(Screen parent) { super("Building materials", parent); }
    @Override protected void build() {
        int half = (panelWidth - 4) / 2;
        button("Choose from building", left, 38, half, () -> minecraft.gui.setScreen(new MaterialPickerScreen(this, true, id -> from = id)));
        button("Choose replacement", left + half + 4, 38, half, () -> minecraft.gui.setScreen(new MaterialPickerScreen(this, false, id -> to = id)));
        button("Sample world block", left, 118, half, () -> { run("materials sample"); minecraft.gui.setScreen(null); });
        button("Use sampled block", left + half + 4, 118, half, () -> { to = ClientState.sampledMaterial(); rebuildWidgets(); }).active = ClientState.sampledMaterial() != null;
        button("Refresh building palette", left, 144, half, () -> run("materials"));
        button("Preview replacement", left + half + 4, 144, half, () -> { run("materials replace " + from + " " + to); minecraft.gui.setScreen(null); })
                .primary().active = from != null && to != null && !from.equals(to);
        button("Texture controls…", left, 172, panelWidth, () -> minecraft.gui.setScreen(new TextureScreen(this, from)));
        button("Back", left, height - 28, panelWidth, this::onClose);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        g.text(font, font.plainSubstrByWidth("From: " + (from == null ? "choose a material above" : from), panelWidth), left, 70, 0xFFEEEEEE);
        g.text(font, font.plainSubstrByWidth("To: " + (to == null ? "choose or sample a block" : to), panelWidth), left, 88, 0xFFEEEEEE);
        text(g, "Preserves compatible stair/log properties. The build zone, selected part, and locks apply. Point at a block before sampling.", 200, height - 48, 0xFFAAAAAA);
    }
}
