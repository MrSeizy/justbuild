package dev.justbuild;

import dev.justbuild.command.JustBuildCommand;
import dev.justbuild.command.ChatAnswerCapture;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigException;
import dev.justbuild.config.ConfigStore;
import dev.justbuild.net.AiCancelPayload;
import dev.justbuild.net.AiProgressPayload;
import dev.justbuild.net.AiRequestPayload;
import dev.justbuild.net.AiResponsePayload;
import dev.justbuild.net.ClientAiPayload;
import dev.justbuild.net.ModelsPayload;
import dev.justbuild.net.PreviewPayload;
import dev.justbuild.net.GhostPreviewPayload;
import dev.justbuild.net.SectionPreviewPayload;
import dev.justbuild.net.ModelsRequestPayload;
import dev.justbuild.net.ProfilesPayload;
import dev.justbuild.net.StatePayload;
import dev.justbuild.session.BuildSessionManager;
import dev.justbuild.session.PlanStore;
import dev.justbuild.world.ZoneWand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JustBuildMod implements ModInitializer {
    public static final String MOD_ID = "justbuild";
    public static final Logger LOGGER = LoggerFactory.getLogger("JustBuild");

    private static volatile BuildSessionManager manager;

    public static BuildSessionManager manager() {
        return manager;
    }

    @Override
    public void onInitialize() {
        ZoneWand.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                JustBuildCommand.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(JustBuildMod::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            BuildSessionManager current = manager;
            if (current != null) {
                current.tick();
            }
        });
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(ChatAnswerCapture::allow);
        PayloadTypeRegistry.clientboundPlay().registerLarge(StatePayload.TYPE, StatePayload.CODEC, StatePayload.MAX_BYTES);
        PayloadTypeRegistry.clientboundPlay().register(ModelsPayload.TYPE, ModelsPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(dev.justbuild.net.ProfileTestPayload.TYPE, dev.justbuild.net.ProfileTestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(dev.justbuild.net.ProfileTestRequestPayload.TYPE, dev.justbuild.net.ProfileTestRequestPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(dev.justbuild.net.ProfileTestRequestPayload.TYPE, (payload, context) ->
                withManager(context.server(), m -> m.testProfile(context.player(), payload)));
        PayloadTypeRegistry.clientboundPlay().register(PreviewPayload.TYPE, PreviewPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(dev.justbuild.net.BuilderBotsPayload.TYPE, dev.justbuild.net.BuilderBotsPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(SectionPreviewPayload.TYPE, SectionPreviewPayload.CODEC, SectionPreviewPayload.MAX_BYTES);
        PayloadTypeRegistry.clientboundPlay().registerLarge(GhostPreviewPayload.TYPE, GhostPreviewPayload.CODEC,
                GhostPreviewPayload.MAX_BYTES);
        PayloadTypeRegistry.clientboundPlay().registerLarge(dev.justbuild.net.LargeGhostPreviewPayload.TYPE,
                dev.justbuild.net.LargeGhostPreviewPayload.CODEC, dev.justbuild.net.LargeGhostPreviewPayload.MAX_BYTES);
        PayloadTypeRegistry.clientboundPlay().registerLarge(dev.justbuild.net.LargeSectionPreviewPayload.TYPE,
                dev.justbuild.net.LargeSectionPreviewPayload.CODEC, dev.justbuild.net.LargeSectionPreviewPayload.MAX_BYTES);
        PayloadTypeRegistry.serverboundPlay().register(ProfilesPayload.TYPE, ProfilesPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ModelsRequestPayload.TYPE, ModelsRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(dev.justbuild.net.BuildRequestPayload.TYPE, dev.justbuild.net.BuildRequestPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(dev.justbuild.net.BuildRequestPayload.TYPE, (payload, context) ->
                withManager(context.server(), m -> m.editorRequest(context.player(), payload)));
        // Players' own AI: the server sends requests to the game and the game answers (see session.RelayHub).
        PayloadTypeRegistry.clientboundPlay().registerLarge(AiRequestPayload.TYPE, AiRequestPayload.CODEC,
                AiRequestPayload.MAX_BYTES);
        PayloadTypeRegistry.clientboundPlay().register(AiCancelPayload.TYPE, AiCancelPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ClientAiPayload.TYPE, ClientAiPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AiProgressPayload.TYPE, AiProgressPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().registerLarge(AiResponsePayload.TYPE, AiResponsePayload.CODEC,
                AiResponsePayload.MAX_BYTES);
        ServerPlayNetworking.registerGlobalReceiver(ClientAiPayload.TYPE, (payload, context) ->
                withManager(context.server(), m -> m.onClientAi(context.player(), payload.json())));
        ServerPlayNetworking.registerGlobalReceiver(AiProgressPayload.TYPE, (payload, context) ->
                withManager(context.server(), m -> m.onAiProgress(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(AiResponsePayload.TYPE, (payload, context) ->
                withManager(context.server(), m -> m.onAiResponse(context.player(), payload)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                withManager(server, m -> m.playerLeft(handler.getPlayer())));
        ServerPlayNetworking.registerGlobalReceiver(ProfilesPayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    BuildSessionManager current = manager;
                    if (current != null) {
                        current.applyProfiles(context.player(), payload.json());
                    }
                }));
        ServerPlayNetworking.registerGlobalReceiver(ModelsRequestPayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    BuildSessionManager current = manager;
                    if (current != null) {
                        current.requestModels(context.player(), payload.id(), payload.json());
                    }
                }));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            BuildSessionManager current = manager;
            if (current != null) {
                current.giveStarterWand(handler.getPlayer());
                current.sendState(handler.getPlayer());
            }
        });
        // The panel's tools and build list depend on the player's dimension.
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
            BuildSessionManager current = manager;
            if (current != null) {
                current.sendState(player);
            }
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            BuildSessionManager current = manager;
            if (current != null) {
                current.playerRespawned(newPlayer);
            }
        });
        LOGGER.info("JustBuild loaded");
    }

    /** Runs on the server thread with the running manager, if any. */
    private static void withManager(MinecraftServer server, java.util.function.Consumer<BuildSessionManager> action) {
        server.execute(() -> {
            BuildSessionManager current = manager;
            if (current != null) {
                action.accept(current);
            }
        });
    }

    private static void start(MinecraftServer server) {
        FabricLoader loader = FabricLoader.getInstance();
        ConfigStore store = new ConfigStore(loader.getConfigDir().resolve("justbuild.json"));
        JustBuildConfig config;
        String problem = null;
        try {
            config = store.load();
        } catch (ConfigException e) {
            problem = e.getMessage();
            LOGGER.error("{} Using defaults until it is fixed and /justbuild reload is run; settings won't save until then.",
                    problem);
            config = JustBuildConfig.defaults();
        }
        BuildSessionManager created = new BuildSessionManager(server, store, config,
                new PlanStore(loader.getGameDir().resolve("justbuild").resolve("plans")),
                server.getWorldPath(LevelResource.ROOT).resolve("justbuild"));
        created.configLoadProblem(problem);
        manager = created;
        LOGGER.info("JustBuild ready (profile: {})", config.activeProfile);
    }

    private static void stop() {
        BuildSessionManager current = manager;
        manager = null;
        if (current != null) {
            current.shutdown();
        }
    }
}
