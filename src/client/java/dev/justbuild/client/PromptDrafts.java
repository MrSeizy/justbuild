package dev.justbuild.client;

import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;

/** Drafts stay on this computer, keyed by world/server and explicit build (or waiting question). */
final class PromptDrafts {
    private static final LinkedHashMap<String, String> drafts = new LinkedHashMap<>();
    private PromptDrafts() { }
    static String key(String target, String question) {
        Minecraft mc = Minecraft.getInstance();
        String server = LocalProfiles.serverKey();
        if (server == null && mc.getSingleplayerServer() != null)
            server = mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().toString();
        return (server == null ? "disconnected" : server) + "\n" + (target == null ? "new" : target)
                + "\n" + dev.justbuild.net.BuildRequestPayload.questionKey(question);
    }
    static String get(String key) { return drafts.getOrDefault(key, ""); }
    static void put(String key, String text) {
        drafts.remove(key);
        if (!text.isEmpty()) drafts.put(key, text);
        while (drafts.size() > 64) drafts.remove(drafts.keySet().iterator().next());
    }
}
