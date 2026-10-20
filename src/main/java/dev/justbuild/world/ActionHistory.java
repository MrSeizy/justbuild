package dev.justbuild.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;

/** Per-actor undo stack, newest first. An entry names an exact version, never a reusable list index. */
public final class ActionHistory {
    public record Entry(String buildId, String versionId) {
        public Entry {
            if (buildId == null || !buildId.matches("[0-9a-f]{8}") || !BuildRecord.Version.validId(versionId)) {
                throw new IllegalArgumentException("Invalid undo version reference.");
            }
        }
        public static Entry of(BuildRecord record) { return new Entry(record.id(), record.current().id()); }
        public boolean matches(BuildRecord record) {
            return buildId.equals(record.id()) && versionId.equals(record.current().id()) && record.canUndoCurrent();
        }
    }

    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final Map<String, Deque<Entry>> byActor = new HashMap<>();
    private final IntSupplier capacity;

    public ActionHistory(IntSupplier capacity) { this.capacity = capacity; }

    public void push(String actor, Entry entry) {
        Deque<Entry> stack = byActor.computeIfAbsent(actor, k -> new ArrayDeque<>());
        stack.addFirst(entry);
        trim(stack);
    }

    public Optional<Entry> peek(String actor) {
        Deque<Entry> stack = byActor.get(actor);
        return stack == null ? Optional.empty() : Optional.ofNullable(stack.peekFirst());
    }

    public Optional<Entry> pop(String actor) {
        Deque<Entry> stack = byActor.get(actor);
        return stack == null ? Optional.empty() : Optional.ofNullable(stack.pollFirst());
    }

    public int size(String actor) {
        Deque<Entry> stack = byActor.get(actor);
        return stack == null ? 0 : stack.size();
    }

    /** Includes offline collaborators; their undo steps protect versions just like the connected actor's steps. */
    public Set<String> referencedVersions(String buildId) {
        return referencedVersions().getOrDefault(buildId, Set.of());
    }

    public Map<String, Set<String>> referencedVersions() {
        Map<String, Set<String>> references = new HashMap<>();
        for (Deque<Entry> stack : byActor.values()) {
            trim(stack);
            for (Entry entry : stack) {
                references.computeIfAbsent(entry.buildId(), id -> new HashSet<>()).add(entry.versionId());
            }
        }
        return references;
    }

    private void trim(Deque<Entry> stack) {
        while (stack.size() > Math.max(1, capacity.getAsInt())) stack.removeLast();
    }

    public void save(Path file) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("format", 2);
        JsonObject actors = new JsonObject();
        byActor.forEach((actor, stack) -> {
            trim(stack);
            JsonArray entries = new JsonArray();
            for (Entry entry : stack) {
                JsonObject value = new JsonObject();
                value.addProperty("build", entry.buildId());
                value.addProperty("version", entry.versionId());
                entries.add(value);
            }
            actors.add(actor, entries);
        });
        root.add("actors", actors);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, root.toString());
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static ActionHistory load(Path file, IntSupplier capacity) throws IOException {
        ActionHistory history = new ActionHistory(capacity);
        if (!Files.exists(file)) return history;
        String text;
        try (var in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Undo history exceeds 16 MB.");
            text = new String(bytes, StandardCharsets.UTF_8);
        }
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            if (!root.has("format")) throw new IOException("Unsupported undo history format: missing");
            int format = root.get("format").getAsBigDecimal().intValueExact();
            if (format != 2) throw new IOException("Unsupported undo history format: " + format);
            JsonObject actors = root.getAsJsonObject("actors");
            for (String actor : actors.keySet()) {
                Deque<Entry> stack = new ArrayDeque<>();
                for (JsonElement element : actors.getAsJsonArray(actor)) {
                    JsonObject value = element.getAsJsonObject();
                    stack.addLast(new Entry(value.get("build").getAsString(), value.get("version").getAsString()));
                }
                history.trim(stack);
                history.byActor.put(actor, stack);
            }
            return history;
        } catch (RuntimeException e) {
            throw new IOException("Invalid undo history: " + e.getMessage(), e);
        }
    }
}
