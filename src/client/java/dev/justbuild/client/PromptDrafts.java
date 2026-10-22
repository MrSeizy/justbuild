package dev.justbuild.client;

import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;

/** Drafts stay on this computer, keyed by world/server and explicit build (or waiting question). */
final class PromptDrafts {
    private static final dev.justbuild.config.PromptDraftStore store = new dev.justbuild.config.PromptDraftStore(
            net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("justbuild-drafts.json"));
    private static LinkedHashMap<String, String> drafts;
    private static boolean dirty;
    private static long nextSave;
    private static String problem;
    private static void load() {
        if (drafts != null) return;
        try { drafts = store.load(); }
        catch (java.io.IOException e) { drafts = new LinkedHashMap<>(); failed(e); }
    }
    private static void failed(java.io.IOException e) {
        if (problem == null) dev.justbuild.JustBuildMod.LOGGER.warn("Prompt drafts could not be saved or loaded: {}", e.toString());
        problem = "Drafts stay in memory: check config/justbuild-drafts.json (" + e.getMessage() + ").";
    }
    static String problem() { return problem; }
    static void tick() { if (dirty && System.nanoTime() >= nextSave) flush(); }
    static void flush() {
        if (!dirty) return;
        try { store.save(drafts); dirty = false; problem = null; }
        catch (java.io.IOException e) { failed(e); }
        nextSave = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    }
    private PromptDrafts() { }
    static String key(String target, String question) {
        Minecraft mc = Minecraft.getInstance();
        String server = LocalProfiles.serverKey();
        if (server == null && mc.getSingleplayerServer() != null)
            server = mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().toString();
        return mc.getUser().getProfileId() + "\n" + (server == null ? "disconnected" : server) + "\n" + (target == null ? "new" : target)
                + "\n" + dev.justbuild.net.BuildRequestPayload.questionKey(question);
    }
    static String get(String key) { load(); return drafts.getOrDefault(key, ""); }
    static void put(String key, String text) {
        load();
        if (get(key).equals(text)) return;
        dirty = true;
        drafts.remove(key);
        if (!text.isEmpty()) drafts.put(key, text);
        while (drafts.size() > dev.justbuild.config.PromptDraftStore.LIMIT) drafts.remove(drafts.keySet().iterator().next());
    }
}
