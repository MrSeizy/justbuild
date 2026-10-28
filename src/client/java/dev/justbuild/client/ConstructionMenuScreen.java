package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/** A quieter background and roomier sheet keep survival controls readable over a busy worksite. */
abstract class ConstructionMenuScreen extends ToolScreen {
    ConstructionMenuScreen(String title, Screen parent) { super(title, parent); }
    @Override protected int preferredPanelWidth() { return 540; }
    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float dt) {
        g.fill(0, 0, width, height, 0x6306090F);
        Glass.panel(g, left - 8, 4, panelWidth + 16, height - 8, 0xF2121924, 0xFF465166);
        g.fill(left + 6, 24, left + panelWidth - 6, 25, 0x665C6A80);
    }
}
