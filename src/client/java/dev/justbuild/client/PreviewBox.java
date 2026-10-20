package dev.justbuild.client;

import dev.justbuild.net.PreviewPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;

/**
 * Draws a waiting preview's bright outline on top of the ghost blocks. The name sits with PreviewControls at the base.
 * Kept until explicitly hidden or disconnected, including while paused.
 * Client thread only.
 */
final class PreviewBox {
    private static final int OUTLINE = 0xFF55FFFF;
    private record Box(String dimension, AABB area, String title) {
    }

    private static Box box;

    private PreviewBox() {
    }

    static void receive(PreviewPayload p) {
        if (!p.show() || box == null || !box.dimension().equals(p.dimension()) || !box.title().equals(p.title())) {
            GhostPreview.clear();
        }
        box = !p.show() ? null : new Box(p.dimension(), new AABB(p.min().getX(), p.min().getY(), p.min().getZ(),
                p.max().getX() + 1, p.max().getY() + 1, p.max().getZ() + 1), p.title());
    }

    /** The waiting preview's box when it is in the player's dimension, else null. */
    static AABB area(Minecraft mc) {
        Box b = box;
        return b == null || mc.level == null || !mc.level.dimension().identifier().toString().equals(b.dimension()) ? null : b.area();
    }

    static void clear() {
        box = null;
        GhostPreview.clear();
    }

    /** End of each client tick: gizmos added now are drawn every frame until the next tick. */
    static void tick(Minecraft mc) {
        Box b = box;
        if (b == null || mc.level == null) {
            return;
        }
        if (!mc.level.dimension().identifier().toString().equals(b.dimension())) {
            return;
        }
        try (var collecting = mc.collectPerTickGizmos()) {
            // The block models carry the fill; the outer frame keeps the full extent easy to see.
            Gizmos.cuboid(b.area(), GizmoStyle.stroke(OUTLINE, 4.0f)).setAlwaysOnTop();
        }
    }
}
