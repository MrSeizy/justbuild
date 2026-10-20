package dev.justbuild.client;

import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Hides the HUD while an JustBuild screen is open: chat, the hotbar, and the bars would show through the glass sheet,
 * and the panel already shows the JustBuild log.
 */
final class GlassHud {
    /**
     * Every VanillaHudElements id as of 26.1.2 except subtitles (accessibility); recheck after an update: a new element
     * wouldn't be hidden.
     */
    private static final List<Identifier> ELEMENTS = List.of(VanillaHudElements.MISC_OVERLAYS,
            VanillaHudElements.CROSSHAIR, VanillaHudElements.SPECTATOR_MENU, VanillaHudElements.HOTBAR,
            VanillaHudElements.ARMOR_BAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR,
            VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR,
            VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.HELD_ITEM_TOOLTIP,
            VanillaHudElements.SPECTATOR_TOOLTIP, VanillaHudElements.MOB_EFFECTS, VanillaHudElements.BOSS_BAR,
            VanillaHudElements.SLEEP, VanillaHudElements.DEMO_TIMER, VanillaHudElements.SCOREBOARD,
            VanillaHudElements.OVERLAY_MESSAGE, VanillaHudElements.TITLE_AND_SUBTITLE, VanillaHudElements.CHAT,
            VanillaHudElements.PLAYER_LIST);

    private GlassHud() {
    }

    static void register() {
        for (Identifier element : ELEMENTS) {
            try {
                HudElementRegistry.replaceElement(element, original -> (graphics, delta) -> {
                    if (!Glass.covers(Minecraft.getInstance().screen)) original.extractRenderState(graphics, delta);
                });
            } catch (IllegalArgumentException e) {
                // another mod removed it: nothing to hide
            }
        }
    }
}
