package dev.justbuild.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Controls floating at a waiting preview, pressed by looking at one and right-clicking (no tool needed): switch
 * between alternatives, Instant Build, Discard, and, for new builds, move (a ◀ ▲ ▼ ▶ pad at its base; 1 block or 5 while
 * sneaking), Up, Down, Rotate, and Snap (onto the ground). They send /justbuild commands, like the panel's
 * buttons.
 */
final class PreviewControls {
    enum Control { PREVIOUS, NEXT, BUILD, DISCARD, UP, DOWN, ROTATE, SNAP, NORTH, SOUTH, WEST, EAST }

    /** A control as last laid out: its world position (billboard center), text, and half size in blocks. */
    private record Placed(Control control, Vec3 pos, String text, int color, float scale, double halfWidth, double halfHeight) { }

    /** Text size: scale/16 blocks per font pixel, so a line is about a third of a block tall. */
    private static final float SCALE = 0.6f;
    /** Arrows are a single glyph, so they are drawn larger to be easy to aim at. */
    private static final float ARROW = 1.0f;
    private static final double REACH = 64;
    private static List<Placed> placed = List.of();
    private static Control hovered;

    private PreviewControls() {
    }

    /** Where a control is now (world position of its label), or null when it isn't shown. */
    static Vec3 position(Control control) {
        for (Placed p : placed) if (p.control() == control) return p.pos();
        return null;
    }

    static Control hovered() {
        return hovered;
    }

    /** Start of each client tick, before vanilla handles keys: a right-click on a control is the control's. */
    static void beforeTick(Minecraft mc) {
        if (ClientState.preview() == null || PreviewBox.area(mc) == null) {
            placed = List.of();
            hovered = null;
            return;
        }
        if (mc.gui.screen() != null || mc.player == null) return;
        hovered = find(mc);
        if (hovered == null) return;
        boolean clicked = false;
        while (mc.options.keyUse.consumeClick()) clicked = true;
        // Vanilla also uses the held item while the button stays down; let go of it until the next press.
        mc.options.keyUse.setDown(false);
        if (clicked) press(mc, hovered);
    }

    /** End of each client tick: lays the controls out around the preview and draws them for the next frames. */
    static void tick(Minecraft mc) {
        ClientState.Preview preview = ClientState.preview();
        AABB box = PreviewBox.area(mc);
        if (preview == null || box == null || mc.player == null
                || mc.gameRenderer.mainCamera().position().distanceTo(box.getCenter()) > REACH + box.getSize()) {
            placed = List.of();
            hovered = null;
            return;
        }
        Vec3 eye = mc.gameRenderer.mainCamera().position();
        Vec3 right = right(eye, box.getCenter());
        List<Placed> next = new ArrayList<>();
        double cx = box.getCenter().x, cz = box.getCenter().z;
        Direction near = Direction.getApproximateNearest(eye.x - cx, 0, eye.z - cz);
        // Keep every control together outside the near face, at the base even for tall or wide previews.
        Vec3 front = side(box, near, box.minY + 0.9);
        Vec3 row1 = front.add(0, 1.05, 0);
        pair(next, row1, right, Control.BUILD, "Instant Build", Glass.PRIMARY_TEXT, Control.DISCARD, "Discard", 0xFFFF7777);
        double top = row1.y + 0.6;
        if (preview.options().size() > 1) {
            String label = "Option " + (preview.option() + 1) + " of " + preview.options().size();
            double half = width(mc, label) / 2 + 0.5;
            Vec3 row = new Vec3(front.x, top, front.z);
            next.add(arrow(mc, Control.PREVIOUS, row.add(right.scale(-half)), "◀"));
            next.add(new Placed(null, row, label, 0xFFDDDDDD, SCALE, 0, 0));
            next.add(arrow(mc, Control.NEXT, row.add(right.scale(half)), "▶"));
            top += 0.6;
        }
        if (preview.movable()) {
            Vec3 row = new Vec3(front.x, top, front.z);
            String[] labels = {"Up", "Down", "Rotate", "Snap"};
            Control[] controls = {Control.UP, Control.DOWN, Control.ROTATE, Control.SNAP};
            double total = 0.5 * (labels.length - 1);
            for (String label : labels) total += width(mc, label);
            double x = -total / 2;
            for (int i = 0; i < labels.length; i++) {
                double w = width(mc, labels[i]);
                next.add(place(mc, controls[i], row.add(right.scale(x + w / 2)), labels[i], 0xFFFFFFFF));
                x += w + 0.5;
            }
            // A compact D-pad: screen-left/right sit beside the stacked away/toward arrows.
            for (Direction d : Direction.Plane.HORIZONTAL) {
                Control control = Control.valueOf(d.name());
                if (d == near) next.add(arrow(mc, control, front.add(0, -0.4, 0), "▼"));
                else if (d == near.getOpposite()) next.add(arrow(mc, control, front.add(0, 0.4, 0), "▲"));
                else {
                    boolean onRight = d.getStepX() * right.x + d.getStepZ() * right.z > 0;
                    next.add(arrow(mc, control, front.add(right.scale(onRight ? 0.65 : -0.65)), onRight ? "▶" : "◀"));
                }
            }
            top += 0.6;
        }
        next.add(new Placed(null, new Vec3(front.x, top, front.z), "Preview: " + preview.title(),
                0xFFFFDD55, SCALE, 0, 0));
        placed = List.copyOf(next);
        hovered = find(mc);
        // Behind an JustBuild screen the controls would draw through its sheet. The layout stays, so a right-click
        // just after the screen closes still lands on a control.
        if (Glass.covers(mc.gui.screen())) return;
        try (var collecting = mc.collectPerTickGizmos()) {
            for (Placed p : placed) {
                int color = p.control() != null && p.control() == hovered ? 0xFFFFDD55 : p.color();
                String text = p.control() != null && p.control() == hovered ? "[" + p.text() + "]" : p.text();
                WorldText.draw(text, p.pos(), TextGizmo.Style.forColorAndCentered(color).withScale(p.scale()));
            }
        }
    }

    /** Two controls side by side, centered on {@code at}. */
    private static void pair(List<Placed> out, Vec3 at, Vec3 right, Control a, String textA, int colorA, Control b,
            String textB, int colorB) {
        Minecraft mc = Minecraft.getInstance();
        double gap = (width(mc, textA) + width(mc, textB)) / 4 + 0.35;
        out.add(place(mc, a, at.add(right.scale(-gap)), textA, colorA));
        out.add(place(mc, b, at.add(right.scale(gap)), textB, colorB));
    }

    private static Placed place(Minecraft mc, Control control, Vec3 pos, String text, int color) {
        return placed(mc, control, pos, text, color, SCALE);
    }

    private static Placed arrow(Minecraft mc, Control control, Vec3 pos, String text) {
        return placed(mc, control, pos, text, 0xFFFFFFFF, ARROW);
    }

    private static Placed placed(Minecraft mc, Control control, Vec3 pos, String text, int color, float scale) {
        // Billboard text is scale/16 blocks per font pixel; a little padding makes it easy to aim at.
        return new Placed(control, pos, text, color, scale, mc.font.width(text) * scale / 32 + 0.12,
                mc.font.lineHeight * scale / 32 + 0.1);
    }

    private static double width(Minecraft mc, String text) {
        return mc.font.width(text) * SCALE / 16;
    }

    /** Just outside the middle of one side of the preview, at height {@code y}. */
    private static Vec3 side(AABB box, Direction d, double y) {
        Vec3 c = box.getCenter();
        return new Vec3(c.x + d.getStepX() * (box.getXsize() / 2 + 1.2), y, c.z + d.getStepZ() * (box.getZsize() / 2 + 1.2));
    }

    /**
     * Rows run across the line from the preview to the player, level with the ground. Not across the camera's yaw:
     * turning your head to aim at a control would swing the row and move the control away.
     */
    private static Vec3 right(Vec3 eye, Vec3 center) {
        Vec3 toward = new Vec3(center.x - eye.x, 0, center.z - eye.z);
        if (toward.lengthSqr() < 1e-4) toward = new Vec3(0, 0, 1);
        toward = toward.normalize();
        return new Vec3(-toward.z, 0, toward.x);
    }

    /** The control under the crosshair: billboards face the camera, so compare in the camera's plane. */
    private static Control find(Minecraft mc) {
        if (placed.isEmpty()) return null;
        var camera = mc.gameRenderer.mainCamera();
        Vec3 eye = camera.position();
        Vec3 look = new Vec3(camera.forwardVector());
        Vec3 up = new Vec3(camera.upVector());
        Vec3 side = look.cross(up);
        Control best = null;
        double closest = Double.MAX_VALUE;
        for (Placed p : placed) {
            if (p.control() == null) continue;
            Vec3 d = p.pos().subtract(eye);
            double ahead = d.dot(look);
            if (ahead <= 0.5 || ahead > REACH) continue;
            Vec3 off = look.scale(ahead).subtract(d);
            double x = Math.abs(off.dot(side)) / p.halfWidth(), y = Math.abs(off.dot(up)) / p.halfHeight();
            // Inside its box; where boxes overlap (seen from a steep angle), the one whose center is nearest wins.
            if (x <= 1 && y <= 1 && x * x + y * y < closest) {
                best = p.control();
                closest = x * x + y * y;
            }
        }
        return best;
    }

    private static void press(Minecraft mc, Control control) {
        ClientState.Preview preview = ClientState.preview();
        if (preview == null || mc.player == null) return;
        // State packets can replace the preview before the next tick lays out its controls.
        if ((control == Control.PREVIOUS || control == Control.NEXT)
                && (preview.options().size() < 2 || preview.option() < 0 || preview.option() >= preview.options().size())) return;
        int step = mc.player.isShiftKeyDown() ? 5 : 1;
        String command = switch (control) {
            case BUILD -> "confirm";
            case DISCARD -> "preview discard";
            case PREVIOUS -> "preview option " + ((preview.option() - 1 + preview.options().size()) % preview.options().size() + 1);
            case NEXT -> "preview option " + ((preview.option() + 1) % preview.options().size() + 1);
            case UP -> "preview move 0 " + step + " 0";
            case DOWN -> "preview move 0 " + -step + " 0";
            case ROTATE -> "preview rotate 90";
            case SNAP -> "preview snap";
            case NORTH, SOUTH, WEST, EAST -> {
                Direction d = Direction.valueOf(control.name());
                yield "preview move " + d.getStepX() * step + " 0 " + d.getStepZ() * step;
            }
        };
        mc.player.connection.sendCommand("justbuild " + command);
    }
}
