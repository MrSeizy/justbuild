package dev.justbuild.client;

import dev.justbuild.world.ZoneWand;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.Identifier;

/** Context and actual bound controls stay visible while the wand is held. */
final class WandHud {
    private static final int BACKGROUND = 0x8C14161E;
    private static final int SECONDARY = 0xFFC0C4CF;
    private record Line(String text, int color) { }

    private WandHud() { }
    static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("justbuild", "wand"), (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.gui.screen() != null || mc.gui.hud.isHidden()) return;
            var stack = ZoneWand.isWand(mc.player.getMainHandItem()) ? mc.player.getMainHandItem() : mc.player.getOffhandItem();
            boolean wand = ZoneWand.isWand(stack);
            String build = ClientState.string("selectedTitle", "");
            if (!wand && build.isBlank() && ClientState.preview() == null) return;
            boolean part = ZoneWand.isPart(stack);
            var region = ClientState.partOutline(); var zone = ClientState.zone(); var draft = ClientState.polygonDraft();
            String selection = part ? region == null ? "No box selected" : (region.max().getX() - region.min().getX() + 1)
                    + " × " + (region.max().getY() - region.min().getY() + 1) + " × " + (region.max().getZ() - region.min().getZ() + 1)
                    : draft != null ? "Polygon · " + draft.points().size() + " corners"
                    : zone == null ? "No zone selected" : (zone.polygon().isEmpty() ? "" : "Polygon · ")
                        + (zone.maxX() - zone.minX() + 1) + " × " + (zone.maxZ() - zone.minZ() + 1)
                        + (zone.minY() == null ? " · auto height" : " · height " + (zone.maxY() - zone.minY() + 1));
            String attack = key(mc.options.keyAttack), use = key(mc.options.keyUse), shift = key(mc.options.keyShift);
            List<Line> lines = new ArrayList<>();
            if (!wand) {
                lines.add(new Line(build.isBlank() ? "New build" : "Editing: " + build, RegionOutline.BUILD));
                if (ClientState.preview() != null) lines.add(new Line("Preview ready · Build it to place", Glass.TITLE));
                else if (ClientState.part() != null) lines.add(new Line("Edits limited to selected part", 0xFFD88BFF));
                lines.add(new Line(JustBuildClient.panelKey() + ": panel · hold wand for outlines", SECONDARY));
            } else if (SelectionHandles.moving()) {
                lines.add(new Line(SelectionHandles.title(), Glass.TITLE));
                lines.add(new Line(SelectionHandles.targetHint(mc), Glass.TEXT));
                lines.add(new Line(use + ": place · " + attack + ": cancel", Glass.TEXT));
                lines.add(new Line("Walk to reposition · Esc: cancel", SECONDARY));
            } else {
                lines.add(new Line((part ? "Box" : "Zone") + " · " + selection, part ? 0xFFD88BFF : Glass.TITLE));
                if (!build.isBlank()) lines.add(new Line("Editing: " + build, RegionOutline.BUILD));
                if (ClientState.part() != null) lines.add(new Line("Purple: edit only this part", 0xFFD88BFF));
                if (!ClientState.locks().isEmpty()) lines.add(new Line("Blue: protected areas stay unchanged", 0xFF5C9DFF));
                if (mc.options.keyShift.isDown()) {
                    lines.add(new Line(use + ": select build · in air: switch mode", Glass.TEXT));
                    if (part && region != null && (mc.player.getMainHandItem().isEmpty()
                            || ZoneWand.isWand(mc.player.getMainHandItem())))
                        lines.add(new Line(attack + ": clear part", Glass.TEXT));
                    lines.add(new Line(shift + " held · " + JustBuildClient.panelKey() + ": panel", SECONDARY));
                } else {
                    lines.add(new Line(attack + ": start · " + use
                            + (part || draft != null ? ": add point" : ": finish zone"), Glass.TEXT));
                    if (part ? ClientState.part() != null : zone != null && draft == null)
                        lines.add(new Line(use + " on corner: resize", Glass.TEXT));
                    lines.add(new Line("Hold " + shift + " for more · " + JustBuildClient.panelKey() + ": panel", SECONDARY));
                }
            }
            // Shrink the HUD gently without making text smaller than the native font at GUI scale 1.
            double guiScale = mc.getWindow().getGuiScale();
            float scale = (float) Math.max(0.75, 1 / guiScale);
            int maxWidth = Math.min(290, (int) ((g.guiWidth() - 14) / scale) - 12);
            List<Line> visible = lines.stream().map(line -> new Line(fit(mc.font, line.text(), maxWidth), line.color())).toList();
            int width = visible.stream().mapToInt(line -> mc.font.width(line.text())).max().orElse(0);
            g.pose().pushMatrix();
            try {
                g.pose().translate(7, 7);
                g.pose().scale(scale, scale);
                Glass.panel(g, 0, 0, width + 12, visible.size() * 10 + 8, BACKGROUND, 0x14FFFFFF);
                for (int i = 0; i < visible.size(); i++) {
                    Line line = visible.get(i);
                    g.text(mc.font, line.text(), 6, 5 + i * 10, line.color());
                }
            } finally {
                g.pose().popMatrix();
            }
        });
    }

    private static String key(KeyMapping mapping) {
        return switch (mapping.saveString()) {
            case "key.mouse.left" -> "LMB";
            case "key.mouse.right" -> "RMB";
            case "key.keyboard.left.shift", "key.keyboard.right.shift" -> "Shift";
            default -> mapping.getTranslatedKeyMessage().getString();
        };
    }

    private static String fit(Font font, String text, int width) {
        return font.width(text) <= width ? text : font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }
}
