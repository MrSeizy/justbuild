package dev.justbuild.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Persistent protected areas. A corrupt store is an error, never silently treated as no protection. */
public final class RegionStore {
    public static final int MAX_REGIONS = 128;
    public record Locked(String id, String owner, String name, BuildRegion region) {
        public Locked {
            if (!id.matches("[a-f0-9]{8,32}") || owner == null || owner.length() > 80) {
                throw new IllegalArgumentException("Invalid locked area.");
            }
            name = BuildRecord.label(name);
        }
    }
    private final Path file;
    public RegionStore(Path file) { this.file = file; }

    public List<Locked> load() throws IOException {
        if (!Files.exists(file)) return List.of();
        if (Files.size(file) > 128_000) throw new IOException("The locked-area file is too large.");
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("format").getAsInt() != 1) throw new IllegalArgumentException("Unsupported locked-area format.");
            JsonArray array = root.getAsJsonArray("regions");
            if (array.size() > MAX_REGIONS) throw new IllegalArgumentException("Too many locked areas.");
            List<Locked> result = new ArrayList<>();
            for (var e : array) {
                JsonObject o = e.getAsJsonObject();
                result.add(new Locked(o.get("id").getAsString(), o.get("owner").getAsString(),
                        o.get("name").getAsString(), BuildRegion.parse(o.getAsJsonObject("region"))));
            }
            return List.copyOf(result);
        } catch (RuntimeException e) { throw new IOException("Could not read locked areas: " + e.getMessage(), e); }
    }

    public void save(List<Locked> regions) throws IOException {
        if (regions.size() > MAX_REGIONS) throw new IOException("Too many locked areas.");
        Files.createDirectories(file.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        JsonArray array = new JsonArray();
        for (Locked lock : regions) {
            JsonObject o = new JsonObject();
            o.addProperty("id", lock.id()); o.addProperty("owner", lock.owner()); o.addProperty("name", lock.name());
            o.add("region", lock.region().toJson()); array.add(o);
        }
        root.add("regions", array);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, root.toString());
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
