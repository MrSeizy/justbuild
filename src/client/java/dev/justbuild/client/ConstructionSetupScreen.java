package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Pin the preview once; every mode can be changed later without losing progress. */
final class ConstructionSetupScreen extends ConstructionMenuScreen {
    ConstructionSetupScreen(Screen parent) { super("Guided Build", parent); }
    @Override protected int preferredPanelWidth() { return 440; }
    @Override protected void build() {
        String[] modes = {"Guide", "Assist", "Cobble"};
        String[] descriptions = {"Build by hand, following the ghost blocks.",
                "Aim and click. Uses blocks from your inventory.", "Let your helper build from a linked chest."};
        for (int i = 0; i < modes.length; i++) {
            String mode = modes[i];
            addRenderableWidget(GlassButton.builder(Component.literal(mode), b -> run("construction start " + mode.toLowerCase(java.util.Locale.ROOT)))
                    .bounds(left, 49 + i * 38, panelWidth, 34).subtitle(descriptions[i]).build());
        }
        button("Back", left, height - 24, panelWidth, this::onClose);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        super.extractRenderState(g, mx, my, dt);
        var preview = ClientState.preview();
        String size = preview == null || !preview.hasCounts() ? "" : " · " + (preview.added() + preview.replaced()) + " blocks, "
                + preview.width() + "×" + preview.height() + "×" + preview.depth();
        g.text(font, font.plainSubstrByWidth("Choose how you want to build this blueprint" + size + ".", panelWidth), left, 33, Glass.TEXT);
        // A project holds other requests back, so say so before the player commits to one.
        int y = text(g, "New JustBuild requests wait until you end this project.", 164, height - 52, Glass.TEXT);
        text(g, "Switch modes any time. Cobble clears plants; dig out terrain yourself.", y, height - 52, Glass.MUTED);
    }
}
