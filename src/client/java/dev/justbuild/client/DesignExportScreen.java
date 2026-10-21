package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

final class DesignExportScreen extends ToolScreen {
    private final ClientState.Design design;
    DesignExportScreen(Screen parent, ClientState.Design design) { super("Export design", parent); this.design = design; }
    @Override protected void build() {
        button("Litematica · .litematic", left, 56, panelWidth, () -> run("design export " + design.id() + " litematic"));
        button("Sponge schematic · .schem", left, 82, panelWidth, () -> run("design export " + design.id() + " schem"));
        button("JustBuild · .justbuild.json", left, 108, panelWidth, () -> run("design export " + design.id()));
        button("Shared library", left, 134, panelWidth, () -> { run("design refresh"); minecraft.gui.setScreen(new LibraryImportScreen(this)); });
        button("Back", left, height - 28, panelWidth, this::onClose);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        g.text(font, font.plainSubstrByWidth(design.name(), panelWidth), left, 36, Glass.TEXT);
        text(g, "Folder: justbuild/library (on the server in multiplayer). "
                + "Schematics store blocks, without entities or container contents.", 164, height - 43, Glass.MUTED);
    }
}
