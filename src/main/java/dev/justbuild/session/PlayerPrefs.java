package dev.justbuild.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.justbuild.prompt.BuildConstraints;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Each player's Auto-build choice and new-build requirements in this world (players.json). */
final class PlayerPrefs {
    private static final Logger LOGGER = LoggerFactory.getLogger("JustBuild");
    private static final int MAX_PLAYERS = 10_000;
    private final Path file;
    private final Set<String> autoBuild = new LinkedHashSet<>();
    private final java.util.Map<String, BuildConstraints> constraints = new java.util.LinkedHashMap<>();

    private PlayerPrefs(Path file) {
        this.file = file;
    }

    /** Settings are conveniences: an unreadable file means defaults (and is replaced on the next change). */
    static PlayerPrefs load(Path file) {
        PlayerPrefs prefs = new PlayerPrefs(file);
        if (!Files.isRegularFile(file)) return prefs;
        try {
            if (Files.size(file) > 2_000_000) throw new IOException("too large");
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("autoBuild") instanceof JsonArray players) {
                if (players.size() > MAX_PLAYERS) throw new IOException("too many players");
                for (var p : players) prefs.autoBuild.add(p.getAsString());
            }
            if (root.get("constraints") instanceof JsonObject settings) {
                if (settings.size() > MAX_PLAYERS) throw new IOException("too many player constraints");
                for (var entry : settings.entrySet()) prefs.constraints.put(entry.getKey(),
                        BuildConstraints.parse(entry.getValue().getAsJsonObject()));
            }
        } catch (IOException | RuntimeException e) {
            // A partially parsed opt-in list is not consent: fail closed for the whole file.
            prefs.autoBuild.clear();
            prefs.constraints.clear();
            LOGGER.warn("Could not read {} ({}); players' settings start from defaults", file, e.toString());
        }
        return prefs;
    }

    boolean autoBuild(String actor) {
        return autoBuild.contains(actor);
    }

    void autoBuild(String actor, boolean on) throws IOException {
        Set<String> next = new LinkedHashSet<>(autoBuild);
        if (on ? !next.add(actor) : !next.remove(actor)) return;
        if (next.size() > MAX_PLAYERS) throw new IOException("Too many player preferences.");
        save(next, constraints);
        autoBuild.clear();
        autoBuild.addAll(next);
    }

    BuildConstraints constraints(String actor) { return constraints.getOrDefault(actor, BuildConstraints.NONE); }

    void constraints(String actor, BuildConstraints value) throws IOException {
        var next = new java.util.LinkedHashMap<>(constraints);
        if (value.equals(BuildConstraints.NONE)) next.remove(actor); else next.put(actor, value);
        if (next.size() > MAX_PLAYERS) throw new IOException("Too many player preferences.");
        save(autoBuild, next);
        constraints.clear(); constraints.putAll(next);
    }

    private void save(Set<String> next, java.util.Map<String, BuildConstraints> settings) throws IOException {
        JsonObject root = new JsonObject();
        JsonArray players = new JsonArray();
        next.forEach(players::add);
        root.add("autoBuild", players);
        JsonObject values = new JsonObject(); settings.forEach((actor, value) -> values.add(actor, value.toJson()));
        root.add("constraints", values);
        if (root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2_000_000)
            throw new IOException("Player preferences are too large.");
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, root.toString());
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
