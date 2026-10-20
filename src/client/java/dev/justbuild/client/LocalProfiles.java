package dev.justbuild.client;

import dev.justbuild.JustBuildMod;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigException;
import dev.justbuild.config.ConfigStore;
import dev.justbuild.config.ProfileEdits;
import dev.justbuild.net.ModelsPayload;
import dev.justbuild.provider.CancellationToken;
import dev.justbuild.provider.CommandLocator;
import dev.justbuild.provider.ProviderException;
import dev.justbuild.provider.Providers;
import dev.justbuild.relay.RelayJson;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * This computer's profiles (config/justbuild.json), used when the game plans builds for a server it does not host.
 * Keys stay on this computer. Client thread, except the model fetch.
 */
final class LocalProfiles {
    private LocalProfiles() {
    }

    static ConfigStore store() {
        return new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("justbuild.json"));
    }

    /** The file as it is now; defaults (and a log line) if it cannot be read. */
    static JustBuildConfig load() {
        try {
            return store().load();
        } catch (ConfigException e) {
            JustBuildMod.LOGGER.warn("{} Using default profiles for your own AI.", e.getMessage());
            return JustBuildConfig.defaults();
        }
    }

    /** Applies the editor's changes; returns an error message, or null when saved. */
    static String save(ProfileEdits.Request request) {
        try {
            JustBuildConfig config = store().load();
            config.replaceProfiles(ProfileEdits.apply(config.profiles, request), request.active());
            store().save(config);
            return null;
        } catch (ConfigException e) {
            return e.getMessage();
        }
    }

    /** Changes one setting and saves; returns an error message, or null. */
    static String update(java.util.function.Consumer<JustBuildConfig> change) {
        try {
            JustBuildConfig config = store().load();
            change.accept(config);
            store().save(config);
            return null;
        } catch (ConfigException e) {
            return e.getMessage();
        }
    }

    /** "Claude (Claude CLI)". */
    static String label(JustBuildConfig.ModelProfile p) {
        return p.name + " (" + Providers.displayName(p.provider) + ")";
    }

    /**
     * What this game tells servers about its own AI: ready only on a server the player turned it on for. Other servers
     * don't learn the profile's name or provider.
     */
    static RelayJson.Hello hello() {
        JustBuildConfig config = load();
        JustBuildConfig.ModelProfile p = config.active();
        boolean allowed = allowedHere(config);
        return new RelayJson.Hello(RelayJson.PROTOCOL, allowed && problem(config, p) == null, allowed ? label(p) : "");
    }

    /** The current server's address as typed in the server list, lower case; null in singleplayer. */
    static String serverKey() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        return server == null || server.ip == null || server.ip.isBlank() ? null
                : server.ip.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether the player turned on "Use my AI here" for the server they are on. Never on a server reached through
     * another server's transfer: Minecraft keeps the original server-list entry for it.
     */
    static boolean allowedHere(JustBuildConfig config) {
        String key = serverKey();
        return config.useMyAi && key != null && !ConnectionOrigin.transferred() && config.myAiServers.contains(key);
    }

    /** Turns "Use my AI here" on or off for the current server; returns an error message, or null. */
    static String toggleHere() {
        String key = serverKey();
        if (key == null) {
            return "Not connected to a server.";
        }
        if (ConnectionOrigin.transferred()) {
            return "Another server sent you here. To use your AI on this server, join it from your server list.";
        }
        return update(c -> {
            c.useMyAi = true;
            if (!c.myAiServers.remove(key)) {
                c.myAiServers.add(key);
            }
        });
    }

    /** Why the active profile cannot plan builds yet, or null if it can. */
    static String problem(JustBuildConfig config, JustBuildConfig.ModelProfile p) {
        try {
            switch (p.provider) {
                case "codex" -> CommandLocator.resolve(config.codex.command, "codex", CommandLocator.defaultSearchDirs());
                case "grok" -> CommandLocator.resolve(config.grok.command, "grok", CommandLocator.defaultSearchDirs());
                case "openai" -> {
                    if (p.model.isBlank()) {
                        return "“" + p.name + "” needs a model.";
                    }
                    String scheme = URI.create(p.baseUrl).getScheme();
                    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                        return "“" + p.name + "” needs an http(s) base URL.";
                    }
                }
                default -> CommandLocator.resolve(config.claude.command, "claude", CommandLocator.defaultSearchDirs());
            }
            return null;
        } catch (ProviderException e) {
            return e.getMessage();
        } catch (IllegalArgumentException e) {
            return "“" + p.name + "” has an invalid base URL.";
        }
    }

    /** Lists models for a draft profile on a background thread; the answer arrives like the server's would. */
    static void fetchModels(ProfileEdits.Edit draft, int requestId) {
        Thread.ofVirtual().name("JustBuild models").start(() -> {
            ModelsPayload reply;
            try {
                JustBuildConfig config = load();
                JustBuildConfig.ModelProfile profile = ProfileEdits.resolve(config.profiles, draft);
                List<String> models = Providers.create(config, profile).models(new CancellationToken());
                reply = new ModelsPayload(requestId, models.subList(0, Math.min(models.size(), ModelsPayload.MAX_MODELS)), "");
            } catch (ConfigException | ProviderException e) {
                reply = new ModelsPayload(requestId, List.of(), e.getMessage());
            } catch (RuntimeException e) {
                JustBuildMod.LOGGER.error("Listing models failed", e);
                reply = new ModelsPayload(requestId, List.of(), e.toString());
            }
            ModelsPayload result = reply;
            Minecraft.getInstance().execute(() -> ModelPickerScreen.receive(result));
        });
    }
}
