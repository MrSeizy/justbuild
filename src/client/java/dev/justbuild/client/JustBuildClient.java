package dev.justbuild.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.justbuild.net.ModelsPayload;
import dev.justbuild.net.PreviewPayload;
import dev.justbuild.net.GhostPreviewPayload;
import dev.justbuild.net.SectionPreviewPayload;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import dev.justbuild.net.StatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Client side: the Right Shift key opens the panel; the panel reads state the server pushes and JustBuild chat lines. */
public final class JustBuildClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("justbuild", "main"));

    private static KeyMapping panelKey;
    static String panelKey() { return panelKey == null ? "Right Shift" : panelKey.getTranslatedKeyMessage().getString(); }

    @Override
    public void onInitializeClient() {
        ZoneWandInput.register();
        GlassHud.register();
        WandHud.register();
        BuilderBots.register();
        KeyMapping open = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.justbuild.panel", InputConstants.Type.KEYBOARD, InputConstants.KEY_RSHIFT, CATEGORY));
        panelKey = open;
        // Unbound until the player sets them: act on a waiting preview without opening the panel.
        KeyMapping build = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.justbuild.build", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
        KeyMapping discard = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.justbuild.discard", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
        // Before vanilla handles keys, so a right-click on a floating preview control isn't also a block placement.
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level != null) {
                PreviewControls.beforeTick(client);
                if (!ZoneWandInput.clearPartBeforeTick(client)) {
                    SelectionHandles.beforeTick(client);
                    ZoneWandInput.beforeTick(client);
                }
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Disconnect can discard queued callbacks; session views must also clear when the world is gone.
            if (client.level == null) {
                PreviewBox.clear();
                ClientState.clear();
                BuilderBots.clear();
                return;
            }
            while (open.consumeClick()) {
                if (client.gui.screen() == null && client.player != null) {
                    client.gui.setScreen(new JustBuildScreen());
                }
            }
            while (build.consumeClick()) {
                if (client.gui.screen() == null && client.player != null && ClientState.preview() != null
                        && PreviewBox.area(client) != null) client.player.connection.sendCommand("justbuild confirm");
            }
            while (discard.consumeClick()) {
                if (client.gui.screen() == null && client.player != null && ClientState.preview() != null
                        && PreviewBox.area(client) != null) client.player.connection.sendCommand("justbuild preview discard");
            }
            PreviewBox.tick(client);
            PreviewControls.tick(client);
            GhostPreview.tick(client);
            BuilderBots.tick(client);
            SelectionLines.beginTick();
            ZoneOutline.tick(client);
            SelectionHandles.tick(client);
            RegionOutline.tick(client);
            SelectionLines.endTick(client);
            RouteOutline.tick(client);
            QualityOutline.tick(client);
        });
        ClientAi.register();
        LevelRenderEvents.COLLECT_SUBMITS.register(GhostPreview::render);
        SelectionLines.register();
        ClientPlayNetworking.registerGlobalReceiver(SectionPreviewPayload.TYPE,
                (payload, context) -> context.client().execute(() -> { FloorPreview.receive(payload);
                    if (context.client().gui.screen() instanceof FloorPreviewScreen floor) floor.stateChanged(); }));
        ClientPlayNetworking.registerGlobalReceiver(GhostPreviewPayload.TYPE,
                (payload, context) -> context.client().execute(() -> GhostPreview.receive(payload)));
        // Registering these tells the server this game takes previews past GhostPreviewPayload.MAX_CELLS.
        ClientPlayNetworking.registerGlobalReceiver(dev.justbuild.net.LargeSectionPreviewPayload.TYPE,
                (payload, context) -> context.client().execute(() -> { FloorPreview.receive(payload.section());
                    if (context.client().gui.screen() instanceof FloorPreviewScreen floor) floor.stateChanged(); }));
        ClientPlayNetworking.registerGlobalReceiver(dev.justbuild.net.LargeGhostPreviewPayload.TYPE,
                (payload, context) -> context.client().execute(() -> GhostPreview.receive(payload.preview())));
        ClientPlayNetworking.registerGlobalReceiver(PreviewPayload.TYPE,
                (payload, context) -> context.client().execute(() -> PreviewBox.receive(payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            PreviewBox.clear();
            ClientState.clear();
            BuilderBots.clear();
        }));
        ClientPlayNetworking.registerGlobalReceiver(StatePayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    ClientState.update(payload.json());
                    if (context.client().gui.screen() instanceof JustBuildScreen panel) {
                        panel.stateChanged();
                    } else if (context.client().gui.screen() instanceof BuildDetailsScreen details) {
                        details.stateChanged();
                    } else if (context.client().gui.screen() instanceof ToolScreen tool) {
                        tool.stateChanged();
                    } else if (context.client().gui.screen() instanceof DesignPlacementScreen design) {
                        design.stateChanged();
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(dev.justbuild.net.ProfileTestPayload.TYPE,
                (payload, context) -> context.client().execute(() -> ProfileTestScreen.receive(payload)));
        ClientPlayNetworking.registerGlobalReceiver(ModelsPayload.TYPE,
                (payload, context) -> context.client().execute(() -> ModelPickerScreen.receive(payload)));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String text = message.getString();
            if (overlay && text.startsWith("JustBuild · ")) {
                ClientState.status(text);
            } else if (!overlay && text.startsWith("[JustBuild]")) {
                ClientState.addLine(message);
            }
        });
    }
}
