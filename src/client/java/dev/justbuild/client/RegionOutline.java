package dev.justbuild.client;

import dev.justbuild.world.BuildRegion;
import dev.justbuild.world.ZoneWand;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Part and protected-area outlines follow the same held-wand visibility rule as ground zones. */
final class RegionOutline {
    /** Blue, so locks never look like the red of removals or walkability markers. */
    private static final int LOCKED = 0xFF5C9DFF;
    static final int BUILD = 0xFF55E3C2;
    private RegionOutline() { }
    static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || !ZoneWand.isWand(mc.player.getMainHandItem())
                && !ZoneWand.isWand(mc.player.getOffhandItem())) return;
        try (var collecting = mc.collectPerTickGizmos()) {
            BuildRegion selected = ClientState.selectedBounds();
            if (selected != null) draw(mc, selected, "Editing: " + ClientState.string("selectedTitle", "Selected build"), BUILD);
            BuildRegion part = SelectionHandles.displayedPart(mc, ClientState.partOutline());
            if (part != null) draw(mc, part, ClientState.flag("selectingPart")
                    ? "Start point · right-click to include more" : "Selected part · " + part.description(), 0xFFD88BFF);
            for (var lock : ClientState.locks()) draw(mc, lock.region(), "Protected: " + lock.name() + " · kept unchanged", LOCKED);
        }
    }
    private static void draw(Minecraft mc, BuildRegion r, String text, int color) {
        if (!mc.level.dimension().equals(r.dimension())) return;
        AABB box = new AABB(r.min().getX(), r.min().getY(), r.min().getZ(), r.max().getX() + 1, r.max().getY() + 1, r.max().getZ() + 1);
        if (box.distanceToSqr(mc.player.position()) > 128 * 128) return;
        SelectionLines.box(box.inflate(0.008), color, 2);
        // The label would draw through an JustBuild screen's sheet; the outline stays visible around it.
        if (Glass.covers(mc.screen)) return;
        WorldText.draw(text, new Vec3(box.getCenter().x, box.maxY + 0.3, box.getCenter().z),
                TextGizmo.Style.forColorAndCentered(color).withScale(TextGizmo.Style.DEFAULT_SCALE * 2));
    }
}
