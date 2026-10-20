package dev.justbuild.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.PlanParser;
import dev.justbuild.plan.PlanResponse;
import dev.justbuild.plan.PlanWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.slf4j.LoggerFactory;

/** World-local reusable plans, independent of the original build and its undo history. */
public final class DesignStore {
    public record Design(String id, String owner, String name, BuildPlan plan, long createdAt, String folder,
            DesignThumbnail.Image thumbnail) {
        public Design(String id, String owner, String name, BuildPlan plan, long createdAt) {
            this(id, owner, name, plan, createdAt, "", DesignThumbnail.Image.EMPTY);
        }
        public Design {
            if (!id.matches("[a-f0-9]{8,32}")) throw new IllegalArgumentException("Invalid design id.");
            name = BuildRecord.label(name);
            folder = folder == null || folder.isBlank() ? "" : BuildRecord.label(folder);
            thumbnail = thumbnail == null ? DesignThumbnail.Image.EMPTY : thumbnail;
        }
    }

    private final Path dir;

    public DesignStore(Path dir) { this.dir = dir; }

    public void save(Design design) throws IOException {
        Files.createDirectories(dir);
        JsonObject o = new JsonObject();
        o.addProperty("format", 2);
        o.addProperty("id", design.id());
        o.addProperty("owner", design.owner());
        o.addProperty("name", design.name());
        o.addProperty("createdAt", design.createdAt());
        o.addProperty("folder", design.folder());
        o.add("thumbnail", design.thumbnail().toJson());
        o.add("plan", PlanWriter.toJson(design.plan()));
        Path target = dir.resolve(design.id() + ".json.gz");
        Path temp = dir.resolve(design.id() + ".json.gz.tmp");
        try (var out = new GZIPOutputStream(Files.newOutputStream(temp))) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        }
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public List<Design> loadAll() {
        List<Design> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) return result;
        try (var files = Files.newDirectoryStream(dir, "*.json.gz")) {
            for (Path file : files) {
                try {
                    JsonObject o = JsonParser.parseString(BuildStore.readText(file)).getAsJsonObject();
                    BuildPlan plan = ((PlanResponse.Build) PlanParser.parse(o.get("plan").toString())).plan();
                    result.add(new Design(o.get("id").getAsString(), o.get("owner").getAsString(),
                            o.get("name").getAsString(), plan, o.get("createdAt").getAsLong(),
                            o.has("folder") ? o.get("folder").getAsString() : "",
                            o.get("thumbnail") instanceof JsonObject thumb ? DesignThumbnail.Image.parse(thumb) : DesignThumbnail.create(plan)));
                } catch (Exception e) {
                    LoggerFactory.getLogger("JustBuild").warn("Skipping unreadable design {}: {}", file.getFileName(), e.toString());
                }
            }
        } catch (IOException e) {
            LoggerFactory.getLogger("JustBuild").warn("Could not list designs: {}", e.toString());
        }
        return List.copyOf(result);
    }
}
