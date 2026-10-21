package dev.justbuild.client;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;

/** Camera-facing world labels with a one-font-pixel dark drop shadow. */
final class WorldText {
    private WorldText() { }

    /** Called inside the caller's gizmo collection; both layers share its lifetime and visibility. */
    static void draw(String text, Vec3 position, TextGizmo.Style style) {
        Gizmos.addGizmo((primitives, opacity) -> {
            // Calculate the offset during extraction, so the shadow follows the billboard between client ticks.
            var camera = Minecraft.getInstance().gameRenderer.mainCamera();
            Vec3 forward = new Vec3(camera.forwardVector());
            Vec3 up = new Vec3(camera.upVector());
            Vec3 right = forward.cross(up);
            double pixel = style.scale() / 16.0;
            Vec3 shadow = position.add(right.subtract(up).scale(pixel)).add(forward.scale(pixel * 0.05));
            int color = ARGB.multiplyAlpha(style.color(), opacity);
            int shadowColor = (color & 0xFF000000) | 0x00101218;
            // Keep the shadow behind the foreground and in the same opacity group, including during fades.
            primitives.addText(shadow, text, new TextGizmo.Style(shadowColor, style.scale(), style.adjustLeft()));
            primitives.addText(position, text, new TextGizmo.Style(color, style.scale(), style.adjustLeft()));
        }).setAlwaysOnTop();
    }
}
