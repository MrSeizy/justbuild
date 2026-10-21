package dev.justbuild.client;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class RouteOutline {
    private RouteOutline() { }
    static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        var route = ClientState.route();
        if (route == null || !route.dimension().equals(mc.level.dimension().identifier().toString())) return;
        int shown = 0;
        // Labels would draw through an JustBuild screen's sheet; the markers stay visible around it.
        boolean labels = !Glass.covers(mc.gui.screen());
        try (var collecting = mc.collectPerTickGizmos()) {
            for (var pos : route.markers()) {
                if (mc.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 96 * 96) continue;
                var box = new AABB(pos.getX() + 0.12, pos.getY() + 0.03, pos.getZ() + 0.12, pos.getX() + 0.88, pos.getY() + 0.08, pos.getZ() + 0.88);
                Gizmos.cuboid(box, GizmoStyle.fill(0x66FF5544)).setAlwaysOnTop();
                Gizmos.cuboid(box, GizmoStyle.stroke(0xFFFF5544, 1.5f)).setAlwaysOnTop();
                if (labels && shown < 4) WorldText.draw("Check route", new Vec3(pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5),
                        TextGizmo.Style.forColorAndCentered(0xFFFF8877));
                if (++shown >= 128) break;
            }
        }
    }
}
