package dev.justbuild.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.PlanException;
import dev.justbuild.plan.PlanParser;
import dev.justbuild.plan.PlanResponse;
import dev.justbuild.plan.PlanWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Portable, data-only designs in one shared game/server folder. Commands never accept arbitrary paths. */
public final class DesignTransfer {
    public static final int MAX_BYTES = 2_000_000;
    public record Imported(String name, String folder, BuildPlan plan, List<String> warnings) {
        public Imported(String name, String folder, BuildPlan plan) { this(name, folder, plan, List.of()); }
    }
    private static final String FILE_NAME = "[A-Za-z0-9][A-Za-z0-9._-]{0,95}\\.(justbuild\\.json|litematic|schem)";
    private final Path directory;
    public DesignTransfer(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }
    public Path directory() { return directory; }

    private Path path(String name) throws IOException {
        if (!name.matches(FILE_NAME)) {
            throw new IOException("Choose a .justbuild.json, .litematic, or .schem file from the shared library; paths are not accepted.");
        }
        // CON, NUL, COM1… are devices on Windows whatever follows the first dot; reading one could block.
        if (name.split("\\.", 2)[0].toUpperCase(java.util.Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]")) {
            throw new IOException("Windows reserves that file name; rename the file.");
        }
        Path result = directory.resolve(name);
        if (Files.isSymbolicLink(result)) throw new IOException("Library files cannot be symbolic links.");
        return result;
    }

    public String exportDesign(DesignStore.Design design) throws IOException { return exportDesign(design, "justbuild.json"); }

    public String exportDesign(DesignStore.Design design, String format) throws IOException {
        if (!List.of("justbuild.json", "litematic", "schem").contains(format)) throw new IOException("Choose justbuild.json, litematic, or schem.");
        Files.createDirectories(directory);
        String slug = design.name().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) slug = "design";
        if (slug.length() > 48) slug = slug.substring(0, 48);
        String name = slug + "-" + design.id().substring(0, 8) + "." + format;
        Path target = path(name);
        JsonObject o = new JsonObject();
        o.addProperty("format", "justbuild-design-1");
        o.addProperty("name", design.name()); o.addProperty("folder", design.folder());
        o.add("plan", PlanWriter.toJson(design.plan()));
        byte[] bytes;
        if (format.equals("justbuild.json")) {
            bytes = o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) throw new IOException("This design is too large to export.");
        } else {
            try { bytes = SchematicCodec.write(design.plan(), design.name(), format); }
            catch (PlanException e) { throw new IOException(e.getMessage(), e); }
        }
        Path temporary = Files.createTempFile(directory, ".export-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        return name;
    }

    public Imported read(String name) throws IOException, PlanException { return read(name, false); }

    public Imported read(String name, boolean includeAir) throws IOException, PlanException {
        Path file = path(name);
        boolean schematic = !name.endsWith(".justbuild.json");
        int limit = schematic ? SchematicCodec.MAX_FILE_BYTES : MAX_BYTES;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > limit) {
            throw new IOException("The library file is missing or exceeds " + (schematic ? "16" : "2") + " MB.");
        }
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("The library file exceeds its size limit.");
            if (schematic) {
                int dot = name.lastIndexOf('.');
                var decoded = SchematicCodec.read(bytes, name.substring(dot + 1), name.substring(0, dot), includeAir);
                return new Imported(decoded.name(), "", decoded.plan(), decoded.warnings());
            }
            JsonObject o = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (!"justbuild-design-1".equals(o.get("format").getAsString())) throw new IOException("Unsupported design file format.");
            if (!(PlanParser.parse(o.get("plan").toString()) instanceof PlanResponse.Build build)) throw new IOException("The file contains no build plan.");
            String folder = o.has("folder") ? o.get("folder").getAsString() : "";
            return new Imported(BuildRecord.label(o.get("name").getAsString()),
                    folder.isBlank() ? "" : BuildRecord.label(folder), build.plan());
        } catch (RuntimeException e) { throw new IOException("Invalid design file: " + e.getMessage(), e); }
    }

    public List<String> files() {
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> p.getFileName().toString()).filter(n -> n.matches(FILE_NAME))
                    .sorted().limit(128).toList();
        } catch (IOException e) { return List.of(); }
    }
}
