package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/**
 * The glass look shared by every JustBuild screen: the panel, text and field colours, and rounded shapes drawn from
 * white nine-slice sprites tinted at draw time (button role fills live in GlassButton; no colour lives in the images,
 * which tools/glass_sprites.py makes).
 */
final class Glass {
    static final int TEXT = 0xFFECECEC;
    static final int MUTED = 0xFFA3A7B8;
    static final int TITLE = 0xFFFFCF6B;
    static final int ACCENT = 0xFFF59E0B;
    static final int PRIMARY = 0xFF34D399;
    static final int PRIMARY_TEXT = 0xFFA7F3D0;
    static final int DANGER_TEXT = 0xFFFF9B9B;
    static final int DISABLED_TEXT = 0xFF6E7282;
    /** Text box hints, as an RGB colour for {@code Component.withColor}. */
    static final int HINT_RGB = 0x8F94A8;
    /** Dark slate at about 84%, with a faint light edge. */
    static final int PANEL = 0xD614161E;
    static final int PANEL_EDGE = 0x17FFFFFF;
    static final int FIELD = 0x10FFFFFF;
    static final int FIELD_EDGE = 0x14FFFFFF;
    static final int FOCUS_EDGE = 0x99F59E0B;

    static final Identifier CUBE = id("glass/cube");
    private static final Identifier PANEL_FILL = id("glass/panel");
    private static final Identifier PANEL_RING = id("glass/panel_ring");
    private static final Identifier PILL_FILL = id("glass/pill");
    private static final Identifier PILL_RING = id("glass/pill_ring");

    private Glass() {
    }

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("justbuild", path);
    }

    /** An JustBuild screen, which hides the HUD and in-world controls behind its sheet. */
    static boolean covers(Screen screen) {
        return screen != null && screen.getClass().getPackageName().equals(Glass.class.getPackageName());
    }

    /** A rounded glass panel. */
    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        panel(g, x, y, w, h, PANEL, PANEL_EDGE);
    }

    /** A rounded panel with its own opacity, for lightweight overlays. */
    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int edge) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL_FILL, x, y, w, h, fill);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL_RING, x, y, w, h, edge);
    }

    /** The full-height sheet behind a screen whose content spans {@code left .. left + panelWidth}. */
    static void sheet(GuiGraphicsExtractor g, int left, int panelWidth, int screenHeight) {
        panel(g, left - 8, 4, panelWidth + 16, screenHeight - 8);
    }

    /** A rounded pill for buttons and fields; a colour with zero alpha skips that layer. */
    static void pill(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int edge) {
        if (ARGB.alpha(fill) != 0) g.blitSprite(RenderPipelines.GUI_TEXTURED, PILL_FILL, x, y, w, h, fill);
        if (ARGB.alpha(edge) != 0) g.blitSprite(RenderPipelines.GUI_TEXTURED, PILL_RING, x, y, w, h, edge);
    }

    /** A text box's inset field, edged in amber while it has the focus. */
    static void field(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean focused) {
        pill(g, x, y, w, h, FIELD, focused ? FOCUS_EDGE : FIELD_EDGE);
    }

    /** A 9×9 icon; white icons take {@code color}, coloured ones are drawn with {@code 0xFFFFFFFF}. */
    static void icon(GuiGraphicsExtractor g, Identifier icon, int x, int y, int color) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, icon, x, y, 9, 9, color);
    }
}
