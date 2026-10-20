package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.justbuild.net.BuildRequestPayload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/** Reusable prompts are inert text until explicitly submitted. They never grant relay consent on load. */
final class SavedPrompts {
    static final int LIMIT = 20;
    private SavedPrompts() { }
    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("justbuild-prompts.json"); }
    static List<String> load() throws java.io.IOException {
        if (!Files.exists(file())) return List.of();
        if (Files.size(file()) > 512 * 1024) throw new java.io.IOException("Saved prompts file is too large");
        try {
            var array = JsonParser.parseString(Files.readString(file())).getAsJsonArray();
            if (array.size() > LIMIT) throw new IllegalArgumentException();
            List<String> values = new ArrayList<>();
            for (var e : array) {
                String text = e.getAsString().strip();
                if (text.isEmpty() || text.length() > BuildRequestPayload.MAX_TEXT) throw new IllegalArgumentException();
                values.add(text);
            }
            return List.copyOf(values);
        } catch (RuntimeException e) { throw new java.io.IOException("Saved prompts file is invalid"); }
    }
    static void add(String text) throws java.io.IOException {
        text = text.strip();
        if (text.isEmpty() || text.length() > BuildRequestPayload.MAX_TEXT) throw new java.io.IOException("Enter a prompt first");
        List<String> values = new ArrayList<>(load()); values.remove(text); values.addFirst(text);
        if (values.size() > LIMIT) throw new java.io.IOException("Remove a saved prompt first (maximum " + LIMIT + ")");
        save(values);
    }
    static void remove(String text) throws java.io.IOException {
        List<String> values = new ArrayList<>(load()); values.remove(text); save(values);
    }
    private static void save(List<String> values) throws java.io.IOException {
        Files.createDirectories(file().getParent());
        Path temp = Files.createTempFile(file().getParent(), "justbuild-prompts", ".tmp");
        try {
            JsonArray array = new JsonArray(); values.forEach(array::add); Files.writeString(temp, array.toString());
            Files.move(temp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }
}
