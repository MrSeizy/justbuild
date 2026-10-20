package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.justbuild.JustBuildMod;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The AI requests the player typed on this computer, kept across sessions, so "/justbuild again" on their own builds still
 * passes PromptCheck without trusting the request text a server stores. Client thread only.
 */
final class RequestHistory {
    private static final int KEEP = 200;
    private static final int MAX_TEXT = 8192; // Includes the server's bounded alternative prefix.
    private static LinkedHashSet<String> requests;

    private RequestHistory() {
    }

    static Set<String> all() {
        load();
        return Set.copyOf(requests);
    }

    static void add(String request) {
        String text = request == null ? "" : request.strip();
        if (text.isEmpty() || text.length() > MAX_TEXT) return;
        load();
        requests.remove(text);
        requests.add(text);
        while (requests.size() > KEEP) requests.remove(requests.iterator().next());
        save();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("justbuild-requests.json");
    }

    private static void load() {
        if (requests != null) return;
        requests = new LinkedHashSet<>();
        Path file = file();
        if (!Files.isRegularFile(file)) return;
        try {
            if (Files.size(file) > 12 * 1024 * 1024) throw new java.io.IOException("too large");
            for (JsonElement e : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
                if (e.isJsonPrimitive() && !e.getAsString().isBlank() && e.getAsString().length() <= MAX_TEXT) {
                    requests.add(e.getAsString().strip());
                    while (requests.size() > KEEP) requests.remove(requests.iterator().next());
                }
            }
        } catch (Exception e) {
            JustBuildMod.LOGGER.warn("Could not read {}; starting a new request history: {}", file, e.toString());
        }
    }

    private static void save() {
        Path file = file();
        try {
            JsonArray array = new JsonArray();
            requests.forEach(array::add);
            Path temp = Files.createTempFile(file.getParent(), "justbuild-requests", ".tmp");
            Files.writeString(temp, array.toString(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            JustBuildMod.LOGGER.warn("Could not save {}: {}", file, e.toString());
        }
    }
}
