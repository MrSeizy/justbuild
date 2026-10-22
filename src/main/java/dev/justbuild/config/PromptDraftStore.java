package dev.justbuild.config;

import com.google.gson.*;
import dev.justbuild.net.BuildRequestPayload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local unsent text only. Loading a draft never authorizes or sends an AI request. */
public final class PromptDraftStore {
    public static final int LIMIT = 64;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final Path file;
    public PromptDraftStore(Path file) { this.file = file; }
    public LinkedHashMap<String, String> load() throws IOException {
        LinkedHashMap<String, String> drafts = new LinkedHashMap<>();
        if (Files.notExists(file)) return drafts;
        try (var in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Prompt drafts file is too large");
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("format").getAsBigDecimal().intValueExact() != 1) throw new IOException("Unsupported prompt drafts format");
            var entries = root.getAsJsonArray("drafts");
            if (entries.size() > LIMIT) throw new IOException("Too many prompt drafts");
            for (var entry : entries) {
                JsonObject item = entry.getAsJsonObject();
                String key = item.get("key").getAsString(), text = item.get("text").getAsString();
                validate(key, text);
                if (drafts.put(key, text) != null) throw new IOException("Duplicate prompt draft context");
            }
            return drafts;
        } catch (RuntimeException e) { throw new IOException("Invalid prompt drafts file", e); }
    }
    public void save(Map<String, String> drafts) throws IOException {
        if (drafts.size() > LIMIT) throw new IOException("Too many prompt drafts");
        load(); // Preserve unreadable/unsupported files, including one changed since the last load.
        JsonObject root = new JsonObject(); root.addProperty("format", 1); JsonArray entries = new JsonArray();
        for (var entry : drafts.entrySet()) {
            validate(entry.getKey(), entry.getValue());
            JsonObject item = new JsonObject(); item.addProperty("key", entry.getKey()); item.addProperty("text", entry.getValue()); entries.add(item);
        }
        root.add("drafts", entries);
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Prompt drafts file is too large");
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "justbuild-drafts-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private static void validate(String key, String text) throws IOException {
        if (key == null || key.isEmpty() || key.length() > 4096 || text == null || text.isEmpty()
                || text.length() > BuildRequestPayload.MAX_TEXT) throw new IOException("Invalid prompt draft length");
    }
}
