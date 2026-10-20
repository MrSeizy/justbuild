package dev.justbuild.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

/**
 * In the client game tests, every JustBuild screen must use the glass widgets: a vanilla button or text box left in
 * one fails the run (the exception on the render thread stops the game) with the screen and label named. Screens are
 * checked when they open or resize and again on every client tick, to catch widgets added by a rebuild.
 */
public final class GlassGuard implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // AFTER_INIT covers setScreen and resize, but not a screen's own rebuildWidgets() (tabs, paging, state changes).
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> check(screen));
        ClientTickEvents.END_CLIENT_TICK.register(client -> check(client.screen));
    }

    private static void check(Screen screen) {
        if (!Glass.covers(screen)) return;
        for (var child : screen.children()) {
            if (child instanceof AbstractButton button && !(child instanceof GlassButton)) {
                throw new AssertionError(screen.getClass().getSimpleName() + " has a vanilla button widget: "
                        + button.getMessage().getString());
            }
            if (child instanceof EditBox box && !(child instanceof GlassEditBox)) {
                throw new AssertionError(screen.getClass().getSimpleName() + " has a vanilla text box: "
                        + box.getMessage().getString());
            }
        }
    }
}
