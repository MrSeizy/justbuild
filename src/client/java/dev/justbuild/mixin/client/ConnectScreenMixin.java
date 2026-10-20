package dev.justbuild.mixin.client;

import dev.justbuild.client.ConnectionOrigin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Notes whether a connection comes from a server's transfer (a non-null TransferState). */
@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void justbuild$noteTransfer(Screen parent, Minecraft minecraft, ServerAddress address, ServerData data,
            boolean quickPlay, TransferState transfer, CallbackInfo ci) {
        ConnectionOrigin.connecting(transfer != null);
    }
}
