package dev.justbuild.client;

import dev.justbuild.prompt.Footprint;
import dev.justbuild.world.BuildRegion;
import dev.justbuild.world.BuildZone;
import dev.justbuild.world.ZoneWand;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Pick up a corner with the wand, then walk to and click its new location. */
final class SelectionHandles {
    private record Handle(boolean part, int corner, Vec3 position) { }
    private record Moving(int corner, BuildZone zone, BuildRegion region, String build, Vec3 pickupEye, Vec3 pickupView) {
        boolean part() { return region != null; }
        BuildZone zoneAt(BlockPos p) { return zone.corner(corner, p.getX(), p.getZ()); }
        BuildRegion regionAt(BlockPos p) { return region.corner(corner, p); }
    }
    private static List<Handle> handles = List.of();
    private static Moving moving;
    private static BuildRegion displayedPart;
    private static BuildZone displayedZone;
    private static final int GOLD = 0xFFFFCF6B;
    private static final double ZONE_HANDLE_LIFT = 0.2;
    private SelectionHandles() { }

    static List<Footprint.Point> corners(BuildZone zone) {
        return !zone.polygon().isEmpty() ? zone.polygon() : List.of(new Footprint.Point(zone.minX(), zone.minZ()),
                new Footprint.Point(zone.maxX(), zone.minZ()), new Footprint.Point(zone.maxX(), zone.maxZ()),
                new Footprint.Point(zone.minX(), zone.maxZ()));
    }
    static Vec3 zoneCornerPosition(BuildZone zone, int corner) {
        var p = corners(zone).get(corner);
        return new Vec3(p.x() + 0.5, zone.displayY() + ZONE_HANDLE_LIFT, p.z() + 0.5);
    }
    /** Handles sit on the box's outer vertices, so even a one-block-thick part has eight distinct handles. */
    static Vec3 partCornerPosition(BuildRegion region, int corner) {
        return Vec3.atLowerCornerOf(region.corner(corner)).add((corner & 1) == 0 ? 0 : 1,
                (corner & 2) == 0 ? 0 : 1, (corner & 4) == 0 ? 0 : 1);
    }
    static void clear() { moving = null; displayedPart = null; displayedZone = null; handles = List.of(); }
    static boolean moving() { return moving != null; }
    static String title() { return "Moving " + (moving.part() ? "box" : "zone") + " corner " + (moving.corner() + 1); }
    static String targetHint(Minecraft mc) {
        BlockPos p = target(mc);
        if (p == null) return "Aim at a block within " + (int) ZoneWand.REACH + " blocks";
        String error = invalid(p);
        return error != null ? error : "New corner: " + (moving.part() ? p.toShortString() : p.getX() + ", " + p.getZ());
    }
    /** The ordinary outline and its handles share one temporary selection until placement or cancellation. */
    static BuildRegion displayedPart(Minecraft mc, BuildRegion selected) {
        updateDisplay(mc);
        return moving != null && moving.part() ? displayedPart : selected;
    }
    static BuildZone displayedZone(Minecraft mc, BuildZone selected) {
        updateDisplay(mc);
        return moving != null && !moving.part() ? displayedZone : selected;
    }
    private static void updateDisplay(Minecraft mc) {
        validate(mc);
        if (moving == null) return;
        BlockPos p = target(mc);
        // Keep the last usable shape while aiming at air or an invalid corner; the HUD explains why it cannot be placed.
        if (p == null || invalid(p) != null) return;
        if (moving.part()) displayedPart = moving.regionAt(p); else displayedZone = moving.zoneAt(p);
    }
    private static boolean available(Minecraft mc) {
        return mc.level != null && mc.player != null && mc.screen == null && ClientState.preview() == null
                && (ZoneWand.isWand(mc.player.getMainHandItem()) || ZoneWand.isWand(mc.player.getOffhandItem()));
    }
    private static boolean partMode(Minecraft mc) {
        return ZoneWand.isPart(ZoneWand.isWand(mc.player.getMainHandItem()) ? mc.player.getMainHandItem() : mc.player.getOffhandItem());
    }
    private static void validate(Minecraft mc) {
        if (moving == null) return;
        if (!available(mc) || partMode(mc) != moving.part()
                || !Objects.equals(moving.build(), ClientState.string("selectedBuild", null))
                || (moving.part() ? !moving.region().equals(ClientState.part()) || !moving.region().dimension().equals(mc.level.dimension())
                        : !moving.zone().equals(ClientState.zone()) || !moving.zone().dimension().equals(mc.level.dimension()))) clear();
    }
    static void beforeTick(Minecraft mc) {
        validate(mc);
        if (!available(mc)) return;
        if (moving != null) {
            boolean cancel = consume(mc.options.keyAttack), place = consume(mc.options.keyUse);
            if (cancel) { clear(); return; }
            BlockPos p = target(mc);
            if (place && p != null && invalid(p) == null) {
                String command = moving.part() ? "box adjust corner " + moving.corner() + " "
                        + p.getX() + " " + p.getY() + " " + p.getZ()
                        : "zone adjust corner " + moving.corner() + " " + p.getX() + " " + p.getZ();
                clear();
                mc.player.connection.sendCommand("justbuild " + command);
            }
            return;
        }
        handles = layout(mc);
        Handle hovered = find(mc);
        if (hovered != null && consume(mc.options.keyUse)) {
            moving = new Moving(hovered.corner(), hovered.part() ? null : ClientState.zone(),
                    hovered.part() ? ClientState.part() : null, ClientState.string("selectedBuild", null),
                    mc.player.getEyePosition(), mc.player.getViewVector(1));
            displayedPart = moving.region();
            displayedZone = moving.zone();
        }
    }
    private static boolean consume(KeyMapping key) {
        boolean clicked = false;
        while (key.consumeClick()) clicked = true;
        // Suppress repeats during a move, but merely hovering must not release an unrelated held button.
        if (clicked || moving != null) key.setDown(false);
        return clicked;
    }
    private static BlockPos target(Minecraft mc) {
        // A handle need not sit on the target block. Keep the selection still until the player changes aim or position.
        if (moving != null && mc.player.getEyePosition().distanceToSqr(moving.pickupEye()) < 1.0e-6
                && mc.player.getViewVector(1).distanceToSqr(moving.pickupView()) < 1.0e-6) {
            if (moving.part()) return moving.region().corner(moving.corner());
            var p = corners(moving.zone()).get(moving.corner());
            return new BlockPos(p.x(), moving.zone().displayY() - 1, p.z());
        }
        var hit = ZoneWandInput.target(mc);
        return hit == null ? null : hit.getBlockPos();
    }
    private static String invalid(BlockPos p) {
        try {
            if (moving.part()) moving.regionAt(p); else moving.zoneAt(p);
            return null;
        } catch (IllegalArgumentException e) { return e.getMessage(); }
    }
    private static List<Handle> layout(Minecraft mc) {
        if (!available(mc) || mc.player.isShiftKeyDown()) return List.of();
        boolean part = partMode(mc);
        List<Handle> next = new ArrayList<>();
        if (part) {
            var region = displayedPart(mc, ClientState.part());
            if (region != null && region.dimension().equals(mc.level.dimension())) {
                for (int i = 0; i < 8; i++) next.add(new Handle(true, i, partCornerPosition(region, i)));
            }
        } else {
            var zone = displayedZone(mc, ClientState.zone());
            if (zone != null && zone.dimension().equals(mc.level.dimension())) {
                for (int i = 0; i < corners(zone).size(); i++)
                    next.add(new Handle(false, i, zoneCornerPosition(zone, i)));
            }
        }
        return List.copyOf(next);
    }
    static void tick(Minecraft mc) {
        validate(mc);
        handles = layout(mc);
        if (!available(mc)) return;
        Handle hover = moving == null ? find(mc) : null;
        BlockPos target = moving == null ? null : target(mc);
        boolean invalidTarget = target != null && invalid(target) != null;
        try (var collecting = mc.collectPerTickGizmos()) {
            for (Handle h : handles) {
                if (mc.player.distanceToSqr(h.position()) > ZoneWand.REACH * ZoneWand.REACH) continue;
                boolean held = moving != null && h.corner() == moving.corner();
                int color = held && invalidTarget ? 0xFFFF7777 : h.part() ? 0xFFD88BFF : h.equals(hover) || held ? GOLD : 0xFFF2F2F2;
                SelectionLines.box(AABB.ofSize(h.position(), 0.35, 0.35, 0.35), color, 2);
                WorldText.draw(h.equals(hover) ? "Adjust corner " + (h.corner() + 1) : Integer.toString(h.corner() + 1),
                        h.position().add(0, 0.35, 0), TextGizmo.Style.forColorAndCentered(color));
            }
        }
    }
    private static Handle find(Minecraft mc) {
        var camera = mc.gameRenderer.getMainCamera(); Vec3 eye = camera.position();
        Vec3 end = eye.add(new Vec3(camera.forwardVector()).scale(ZoneWand.REACH));
        Handle best = null; double distance = Double.MAX_VALUE;
        for (Handle h : handles) {
            var hit = AABB.ofSize(h.position(), 0.6, 0.8, 0.6).clip(eye, end);
            if (hit.isPresent() && eye.distanceToSqr(hit.get()) < distance) { best = h; distance = eye.distanceToSqr(hit.get()); }
        }
        return best;
    }
}
