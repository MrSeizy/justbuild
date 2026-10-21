package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/** Start and finish wand-drawn polygons; corners are moved with in-world handles. */
final class PolygonScreen extends ToolScreen {
    PolygonScreen(Screen parent) { super("Polygon footprint", parent); }
    @Override protected void build() {
        int half = (panelWidth - 4) / 2;
        button("Start polygon with wand", left, 70, panelWidth, () -> {
            run("zone adjust polygon start"); run("zone wand"); minecraft.gui.setScreen(null);
        });
        var draft = ClientState.polygonDraft();
        button("Undo last corner", left, 98, half, () -> run("zone adjust polygon undo"))
                .active = draft != null && !draft.points().isEmpty();
        button("Finish polygon", left + half + 4, 98, half, () -> run("zone adjust polygon finish"))
                .active = draft != null && draft.points().size() >= 3;
        button("Cancel polygon", left, 126, panelWidth, () -> run("zone adjust polygon cancel")).active = draft != null;
        button("Back", left, height - 28, panelWidth, this::onClose);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        g.text(font, "Draw 3–32 corners in boundary order", left, 40, Glass.MUTED);
        text(g, "Right-click each corner with the Zone wand. Finish here to close the polygon. Edges cannot cross. "
                + (ClientState.polygonDraft() == null ? "" : "Selected: " + ClientState.polygonDraft().points().size() + " corners."),
                158, height - 48, Glass.MUTED);
    }
}
