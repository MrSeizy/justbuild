package dev.justbuild.client;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;

final class QualityOutline {
    private static String selected;
    private QualityOutline() { }
    static void highlight(String id) { selected = id; }
    static void tick(Minecraft mc) {
        if (selected == null || mc.level == null || mc.player == null) return;
        var issue = ClientState.qualityIssues().stream().filter(i -> i.id().equals(selected)).findFirst();
        if (issue.isEmpty()) { selected = null; return; }
        var value = issue.get(); var pos = value.position();
        if (pos == null || !mc.level.dimension().identifier().toString().equals(value.dimension()) || mc.player.distanceToSqr(pos.getCenter()) > 128*128) return;
        try (var collecting = mc.collectPerTickGizmos()) {
            Gizmos.cuboid(new AABB(pos).inflate(0.1), GizmoStyle.stroke(0xFFFF7766,3)).setAlwaysOnTop();
            if (!Glass.covers(mc.screen)) WorldText.draw("Quality check · " + pos.toShortString(), pos.getCenter().add(0,1.2,0),
                    TextGizmo.Style.forColorAndCentered(0xFFFFAA88));
        }
    }
}
