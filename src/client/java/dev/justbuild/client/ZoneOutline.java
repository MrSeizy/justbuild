package dev.justbuild.client;

import com.google.gson.JsonObject;
import dev.justbuild.prompt.Footprint;
import dev.justbuild.world.BuildZone;
import dev.justbuild.world.ZoneWand;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** White zone edges; optional height bounds and unfinished polygon edges use the same selection data. */
final class ZoneOutline {
    private static BuildZone footprint;
    private ZoneOutline() { }
    static void receive(JsonObject zone) {
        footprint = null;
        try { if (zone != null) footprint = BuildZone.fromJson(zone); } catch (RuntimeException ignored) { }
    }
    static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || !ZoneWand.isWand(mc.player.getMainHandItem())
                && !ZoneWand.isWand(mc.player.getOffhandItem())) return;
        var draft = ClientState.polygonDraft();
        try (var collecting = mc.collectPerTickGizmos()) {
            if (draft != null && mc.level.dimension().identifier().toString().equals(draft.dimension())) {
                edges(draft.points(), draft.y() + 0.05, false); return;
            }
            BuildZone zone = SelectionHandles.displayedZone(mc, footprint);
            if (zone == null || !mc.level.dimension().equals(zone.dimension())) return;
            double y = zone.minY() == null ? zone.displayY() + 0.03 : zone.minY();
            double top = zone.maxY() == null ? y : zone.maxY() + 1;
            if (zone.polygon().isEmpty()) {
                SelectionLines.box(new AABB(zone.minX(), y, zone.minZ(), zone.maxX() + 1, top, zone.maxZ() + 1), 0xFFF2F2F2, 2);
            } else {
                edges(zone.polygon(), y, true);
                if (zone.minY() != null) {
                    edges(zone.polygon(), top, true);
                    for (var p : zone.polygon()) SelectionLines.line(new Vec3(p.x() + 0.5, y, p.z() + 0.5),
                            new Vec3(p.x() + 0.5, top, p.z() + 0.5), 0xFFF2F2F2, 2);
                }
            }
        }
    }
    private static void edges(List<Footprint.Point> points, double y, boolean close) {
        for (int i = 0; i < points.size(); i++) {
            var a = points.get(i);
            Gizmos.cuboid(new AABB(a.x() + 0.35, y - 0.1, a.z() + 0.35, a.x() + 0.65, y + 0.2, a.z() + 0.65),
                    GizmoStyle.fill(0x88FFFFFF)).setAlwaysOnTop();
            if (!close && i == points.size() - 1) break;
            var b = points.get((i + 1) % points.size());
            SelectionLines.line(new Vec3(a.x() + 0.5, y, a.z() + 0.5), new Vec3(b.x() + 0.5, y, b.z() + 0.5),
                    0xFFF2F2F2, 2);
        }
    }
}
