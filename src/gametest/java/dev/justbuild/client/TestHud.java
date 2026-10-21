package dev.justbuild.client;

import net.minecraft.client.Minecraft;

/** Sets the desired HUD visibility for captures without depending on its previous toggle state. */
public final class TestHud {
    private TestHud() { }

    public static void hidden(Minecraft minecraft, boolean hidden) {
        if (minecraft.gui.hud.isHidden() != hidden) minecraft.gui.hud.toggle();
    }
}
