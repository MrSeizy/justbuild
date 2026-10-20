package dev.justbuild.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.justbuild.plan.PlanParser;
import dev.justbuild.plan.PlanResponse;
import dev.justbuild.plan.PlanWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Saves build records as gzip JSON under <world>/justbuild/builds/<id>.json.gz. */
public final class BuildStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("JustBuild");
    private static final String SUFFIX = ".json.gz";

    private final Path dir;
    private final int maxTextBytes;
    private List<String> loadProblems = List.of();

    /** Missing records may contain ownership boundaries, so callers must disable placement after a partial load. */
    public List<String> loadProblems() { return loadProblems; }
    /**
     * Turning a large record into text and compressing it takes most of a save (about 180 of 200 ms at 250,000 blocks),
     * so that happens here. One thread, in submission order: a queued save never lands after a later save or a delete.
     */
    private final ExecutorService writer = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("JustBuild build writer").factory());

    public BuildStore(Path dir) {
        this(dir, MAX_TEXT_BYTES);
    }

    BuildStore(Path dir, int maxTextBytes) {
        this.dir = dir;
        this.maxTextBytes = maxTextBytes;
    }

    /** Writes the record before returning, after anything already queued; for callers whose next step needs it saved. */
    public void save(BuildRecord record) throws IOException {
        String id = record.id();
        JsonObject json = toJson(record);
        await(submit(() -> { write(id, json); return null; }));
    }

    /**
     * Takes the record's snapshot now, on this thread, and writes it on the store's writer. {@code onFailure} runs on
     * the writer thread. False means the store is closed and nothing was queued.
     */
    public boolean saveLater(BuildRecord record, Consumer<IOException> onFailure) {
        String id = record.id();
        JsonObject json = toJson(record);
        try {
            writer.execute(() -> {
                try {
                    write(id, json);
                } catch (IOException e) {
                    onFailure.accept(e);
                } catch (RuntimeException e) {
                    onFailure.accept(new IOException(e));
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /** Waits until every queued save and delete is written. */
    public void flush() {
        try {
            await(submit(() -> null));
        } catch (IOException closed) {
            // close() already waited for everything queued.
        }
    }

    /** Writes everything still queued, then refuses further saves. */
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.MINUTES)) {
                LOGGER.error("Build saves were still being written after 5 minutes; the newest changes may be lost");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Future<Void> submit(Callable<Void> task) throws IOException {
        try {
            return writer.submit(task);
        } catch (RejectedExecutionException e) {
            throw new IOException("the build store is closed");
        }
    }

    private static void await(Future<Void> done) throws IOException {
        try {
            done.get();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while saving");
        }
    }

    private void write(String id, JsonObject json) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(id + SUFFIX);
        Path temp = dir.resolve(id + SUFFIX + ".tmp");
        byte[] text = fitted(id, json);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(temp))) {
            out.write(text);
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * The record's text within what {@link #loadAll} will read, so a saved build always loads again. Past that, older
     * versions drop their exact undo states, oldest first; undoing them then falls back to their plans. A record that
     * still cannot fit is refused, which keeps the last readable copy on disk.
     */
    private byte[] fitted(String id, JsonObject json) throws IOException {
        byte[] text = json.toString().getBytes(StandardCharsets.UTF_8);
        if (text.length <= maxTextBytes) return text;
        JsonArray versions = json.getAsJsonArray("versions");
        long size = text.length;
        int dropped = 0;
        for (int i = 0; size > maxTextBytes && i < versions.size() - 1; i++) {
            JsonElement undo = versions.get(i).getAsJsonObject().remove("undoStates");
            if (undo != null) {
                // Positions and registry ids are ASCII, so characters are bytes.
                size -= undo.toString().length() + ",\"undoStates\":".length();
                dropped++;
            }
        }
        if (dropped > 0) {
            text = json.toString().getBytes(StandardCharsets.UTF_8);
            LOGGER.warn("Build record {} dropped exact undo states from {} older versions to stay under {} bytes",
                    id, dropped, maxTextBytes);
        }
        if (text.length > maxTextBytes) {
            throw new IOException("build " + id + " would be " + (text.length >> 20)
                    + " MB uncompressed, past the " + (maxTextBytes >> 20) + " MB it could be read back at");
        }
        return text;
    }

    /** Removes the record's file after any save still queued for it, so that save cannot bring it back. */
    public void delete(String id) throws IOException {
        await(submit(() -> { Files.deleteIfExists(dir.resolve(id + SUFFIX)); return null; }));
    }

    public List<BuildRecord> loadAll() {
        flush();
        List<BuildRecord> records = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        loadProblems = List.of();
        if (!Files.isDirectory(dir)) {
            if (!Files.notExists(dir)) loadProblems = List.of("builds directory cannot be read");
            return records;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
            for (Path file : files) {
                try {
                    JsonObject json = JsonParser.parseString(readText(file, maxTextBytes)).getAsJsonObject();
                    BuildRecord record = fromJson(json);
                    if (!file.getFileName().toString().equals(record.id() + SUFFIX)) {
                        throw new IOException("record id does not match its filename");
                    }
                    records.add(record);
                } catch (IOException | RuntimeException e) {
                    problems.add(file.getFileName().toString());
                    LOGGER.warn("Skipping unreadable build record {}: {}", file.getFileName(), e.toString());
                }
            }
        } catch (IOException e) {
            problems.add("builds directory cannot be read");
            LOGGER.warn("Could not list {}: {}", dir, e.toString());
        }
        loadProblems = List.copyOf(problems);
        return records;
    }

    /** The most a saved build or design may decompress to; a larger file is skipped rather than filling memory. */
    static final int MAX_TEXT_BYTES = 64 * 1024 * 1024;

    static String readText(Path file) throws IOException {
        return readText(file, MAX_TEXT_BYTES);
    }

    private static String readText(Path file, int maxTextBytes) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            byte[] bytes = in.readNBytes(maxTextBytes + 1);
            if (bytes.length > maxTextBytes) {
                throw new IOException("larger than " + maxTextBytes / (1024 * 1024) + " MB uncompressed");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    static JsonObject toJson(BuildRecord record) {
        JsonObject o = new JsonObject();
        o.addProperty("format", 5);
        o.addProperty("id", record.id());
        o.addProperty("owner", record.owner());
        o.addProperty("dimension", record.dimension().identifier().toString());
        o.add("origin", pos(record.origin()));
        o.addProperty("facing", record.facing().getName());
        if (record.name() != null) o.addProperty("name", record.name());
        o.addProperty("adopted", record.adopted());
        if (record.typedRequest() != null) o.addProperty("typedRequest", record.typedRequest());
        o.addProperty("areaSaved", record.hasSavedZone());
        if (record.zone() != null) o.add("zone", record.zone().toJson());
        o.add("constraints", record.constraints().toJson());
        JsonObject parts = new JsonObject(); record.namedParts().forEach((name, region) -> parts.add(name, region.toJson()));
        o.add("parts", parts);
        JsonArray checkpoints = new JsonArray();
        for (BuildRecord.Checkpoint c : record.checkpoints()) {
            JsonObject checkpoint = new JsonObject();
            checkpoint.addProperty("name", c.name());
            checkpoint.addProperty("createdAt", c.createdAt());
            checkpoint.add("plan", PlanWriter.toJson(c.plan()));
            checkpoints.add(checkpoint);
        }
        o.add("checkpoints", checkpoints);
        JsonArray versions = new JsonArray();
        for (BuildRecord.Version v : record.versions()) {
            JsonObject version = new JsonObject();
            version.addProperty("id", v.id());
            if (v.predecessorId() != null) version.addProperty("predecessor", v.predecessorId());
            version.addProperty("number", v.number());
            version.addProperty("request", v.request());
            version.addProperty("createdAt", v.createdAt());
            version.add("plan", PlanWriter.toJson(v.plan()));
            if (!v.undoStates().isEmpty()) version.add("undoStates", states(v.undoStates()));
            versions.add(version);
        }
        o.add("versions", versions);
        JsonArray baseline = new JsonArray();
        record.baseline().forEach((p, state) -> {
            JsonArray entry = pos(p);
            entry.add(BlockStateParser.serialize(state));
            baseline.add(entry);
        });
        o.add("baseline", baseline);
        return o;
    }

    static BuildRecord fromJson(JsonObject o) throws IOException {
        if (!o.has("format")) throw new IOException("Unsupported build format: missing");
        int format = o.get("format").getAsBigDecimal().intValueExact();
        if (format != 5) throw new IOException("Unsupported build format: " + format);
        List<BuildRecord.Version> versions = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("versions")) {
            JsonObject v = e.getAsJsonObject();
            try {
                var plan = ((PlanResponse.Build) PlanParser.parse(v.getAsJsonObject("plan").toString())).plan();
                String versionId = v.get("id").getAsString();
                String predecessor = v.has("predecessor") ? v.get("predecessor").getAsString() : null;
                int number = v.get("number").getAsBigDecimal().intValueExact();
                if (number < 1) throw new IOException("Invalid version number.");
                versions.add(new BuildRecord.Version(versionId, predecessor, number, v.get("request").getAsString(),
                        plan, v.get("createdAt").getAsLong(),
                        v.get("undoStates") instanceof JsonArray undo ? readStates(undo) : Map.of()));
            } catch (Exception ex) {
                throw new IOException("version does not parse: " + ex.getMessage());
            }
        }
        if (versions.isEmpty()) {
            throw new IOException("record has no versions");
        }
        Map<BlockPos, BlockState> baseline = new HashMap<>();
        int skipped = 0;
        for (JsonElement e : o.getAsJsonArray("baseline")) {
            JsonArray entry = e.getAsJsonArray();
            BlockPos p = new BlockPos(entry.get(0).getAsInt(), entry.get(1).getAsInt(), entry.get(2).getAsInt());
            var state = BlockResolver.parse(entry.get(3).getAsString());
            if (state.isPresent()) {
                baseline.put(p, state.get());
            } else {
                skipped++;
            }
        }
        String id = o.get("id").getAsString();
        // The id names the record's file; anything but a plain id could point outside the builds folder.
        if (!id.matches("[0-9a-f]{8}")) {
            throw new IOException("build id \"" + id + "\" is not a plain id");
        }
        Direction facing = Direction.byName(o.get("facing").getAsString());
        if (facing == null || facing.getAxis().isVertical()) {
            throw new IOException("build facing \"" + o.get("facing").getAsString() + "\" is not north, south, east, or west");
        }
        if (skipped > 0) {
            LOGGER.warn("Build {}: skipped {} baseline blocks that no longer exist", id, skipped);
        }
        JsonArray origin = o.getAsJsonArray("origin");
        BuildRecord record;
        try {
            record = new BuildRecord(id, o.get("owner").getAsString(),
                    ResourceKey.create(Registries.DIMENSION, Identifier.parse(o.get("dimension").getAsString())),
                    new BlockPos(origin.get(0).getAsInt(), origin.get(1).getAsInt(), origin.get(2).getAsInt()),
                    facing, versions, baseline);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid build version chain: " + e.getMessage(), e);
        }
        if (o.has("name")) record.rename(o.get("name").getAsString());
        if (o.has("adopted")) record.adopted(o.get("adopted").getAsBoolean());
        if (o.has("typedRequest")) record.typedRequest(o.get("typedRequest").getAsString());
        if (o.has("zone")) record.zone(BuildZone.fromJson(o.getAsJsonObject("zone")));
        else if (o.has("areaSaved") && o.get("areaSaved").getAsBoolean()) record.zone(null);
        if (o.has("constraints")) record.constraints(dev.justbuild.prompt.BuildConstraints.parse(o.getAsJsonObject("constraints")));
        if (o.has("parts")) {
            JsonObject parts = o.getAsJsonObject("parts");
            if (parts.size() > 32) throw new IOException("Too many named parts.");
            for (var part : parts.entrySet()) record.savePart(part.getKey(), BuildRegion.parse(part.getValue().getAsJsonObject()));
        }
        if (o.has("checkpoints")) {
            for (JsonElement e : o.getAsJsonArray("checkpoints")) {
                JsonObject c = e.getAsJsonObject();
                try {
                    var plan = ((PlanResponse.Build) PlanParser.parse(c.getAsJsonObject("plan").toString())).plan();
                    record.addCheckpoint(new BuildRecord.Checkpoint(c.get("name").getAsString(), plan,
                            c.get("createdAt").getAsLong()));
                } catch (Exception ex) {
                    LOGGER.warn("Build {}: skipped unreadable checkpoint: {}", id, ex.toString());
                }
            }
        }
        return record;
    }

    private static JsonArray states(Map<BlockPos, BlockState> values) {
        JsonArray array = new JsonArray();
        values.forEach((p, state) -> { JsonArray entry = pos(p); entry.add(BlockStateParser.serialize(state)); array.add(entry); });
        return array;
    }

    private static Map<BlockPos, BlockState> readStates(JsonArray array) throws IOException {
        if (array.size() > 1_000_000) throw new IOException("Too many saved undo states.");
        Map<BlockPos, BlockState> states = new HashMap<>();
        for (JsonElement e : array) {
            JsonArray a = e.getAsJsonArray();
            BlockPos p = new BlockPos(a.get(0).getAsBigDecimal().intValueExact(), a.get(1).getAsBigDecimal().intValueExact(),
                    a.get(2).getAsBigDecimal().intValueExact());
            // A block from a removed mod: that spot is left as it is on undo, rather than losing the whole build.
            var state = BlockResolver.parse(a.get(3).getAsString());
            if (state.isEmpty()) continue;
            if (states.put(p, state.get()) != null) throw new IOException("Duplicate saved undo position.");
        }
        return states;
    }

    private static JsonArray pos(BlockPos p) {
        JsonArray a = new JsonArray();
        a.add(p.getX());
        a.add(p.getY());
        a.add(p.getZ());
        return a;
    }
}
