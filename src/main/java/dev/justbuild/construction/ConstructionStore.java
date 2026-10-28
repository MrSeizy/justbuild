package dev.justbuild.construction;

import com.google.gson.*;
import dev.justbuild.world.BlockResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.state.BlockState;

/** Independent versioned files leave released builds, previews, and undo formats unchanged. */
public final class ConstructionStore {
    private static final int LIMIT = 48 * 1024 * 1024;
    private final Path directory;
    /** Owners whose file this store has read or written; saving those skips re-parsing the whole blueprint. */
    private final Set<UUID> verified = new HashSet<>();
    public ConstructionStore(Path directory) { this.directory = directory; }
    public Path file(UUID owner) { return directory.resolve(owner + ".json.gz"); }
    public ConstructionProject load(UUID owner) throws IOException {
        verified.remove(owner);
        if (Files.notExists(file(owner))) return null;
        try (var in = new GZIPInputStream(Files.newInputStream(file(owner)))) {
            byte[] data = in.readNBytes(LIMIT + 1);
            if (data.length > LIMIT) throw new IOException("Construction file exceeds size limit");
            JsonObject o = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.get("format").getAsBigDecimal().intValueExact() != 1 || !owner.toString().equals(o.get("owner").getAsString()))
                throw new IOException("Unsupported construction format or owner; file preserved");
            JsonArray cells = o.getAsJsonArray("cells"), palette = o.getAsJsonArray("palette");
            if (cells.size() > dev.justbuild.config.JustBuildConfig.MAX_BLOCKS_LIMIT || palette.size() > 4096)
                throw new IOException("Construction exceeds blueprint limits");
            List<BlockState> states = new ArrayList<>();
            for (var s : palette) states.add(BlockResolver.parse(s.getAsString()).orElseThrow());
            Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
            for (var cell : cells) {
                JsonArray c = cell.getAsJsonArray();
                if (c.size() != 4 || blocks.put(pos(c), states.get(integer(c.get(3)))) != null)
                    throw new IOException("Duplicate or invalid construction cell");
            }
            var p = new ConstructionProject(UUID.fromString(o.get("id").getAsString()), owner, o.get("title").getAsString(),
                    ResourceKey.create(Registries.DIMENSION, Identifier.parse(o.get("dimension").getAsString())), blocks,
                    ConstructionProject.Mode.valueOf(o.get("mode").getAsString()));
            p.currentLayer = o.get("currentLayer").getAsBoolean();
            if (o.has("chest")) {
                JsonArray chest = o.getAsJsonArray("chest");
                if (chest.size() != 3) throw new IOException("Invalid supply chest position");
                p.chest = pos(chest); validateChest(p);
            }
            // Restarts never resume unattended placement.
            verified.add(owner); return p;
        } catch (RuntimeException e) { throw new IOException("Invalid construction file; file preserved", e); }
    }
    public void save(ConstructionProject p) throws IOException {
        validateChest(p);
        if (!verified.contains(p.owner)) load(p.owner); // Refuse to overwrite unreadable or future formats.
        JsonObject o = new JsonObject(); o.addProperty("format", 1); o.addProperty("owner", p.owner.toString());
        o.addProperty("id", p.id.toString()); o.addProperty("title", p.title);
        o.addProperty("dimension", p.dimension.identifier().toString()); o.addProperty("mode", p.mode.name());
        o.addProperty("currentLayer", p.currentLayer);
        if (p.chest != null) o.add("chest", position(p.chest));
        Map<BlockState, Integer> ids = new LinkedHashMap<>(); JsonArray cells = new JsonArray();
        p.blueprint.forEach((pos, state) -> { int id = ids.computeIfAbsent(state, ignored -> ids.size());
            JsonArray cell = position(pos); cell.add(id); cells.add(cell); });
        JsonArray palette = new JsonArray(); ids.keySet().forEach(s -> palette.add(BlockStateParser.serialize(s)));
        o.add("palette", palette); o.add("cells", cells);
        byte[] bytes = o.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT) throw new IOException("Construction file exceeds size limit");
        Files.createDirectories(directory); Path temp = Files.createTempFile(directory, "construction-", ".tmp");
        try {
            try (var out = new GZIPOutputStream(Files.newOutputStream(temp))) { out.write(bytes); }
            Files.move(temp, file(p.owner), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            verified.add(p.owner);
        } finally { Files.deleteIfExists(temp); }
    }
    public void delete(UUID owner) throws IOException { verified.remove(owner); Files.deleteIfExists(file(owner)); }
    /** Keeps an unreadable file under a new name so a newer JustBuild, or a person, can still recover it. */
    public Path setAside(UUID owner) throws IOException {
        verified.remove(owner);
        if (Files.notExists(file(owner))) return null;
        Path kept = directory.resolve(owner + ".unreadable-" + System.currentTimeMillis() + ".json.gz");
        Files.move(file(owner), kept, StandardCopyOption.ATOMIC_MOVE);
        return kept;
    }
    private static void validateChest(ConstructionProject p) throws IOException {
        if (p.chest == null) return;
        BlockPos c = p.chest;
        if (Math.abs((long) c.getX()) > 30_000_000 || Math.abs((long) c.getZ()) > 30_000_000 || Math.abs((long) c.getY()) > 4096
                || new net.minecraft.world.phys.AABB(net.minecraft.world.phys.Vec3.atLowerCornerOf(p.min),
                        net.minecraft.world.phys.Vec3.atLowerCornerOf(p.max.offset(1, 1, 1))).distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(c)) > 32 * 32)
            throw new IOException("Supply chest is outside the worksite range");
    }
    static JsonArray position(BlockPos p) { JsonArray a = new JsonArray(); a.add(p.getX()); a.add(p.getY()); a.add(p.getZ()); return a; }
    static BlockPos pos(JsonArray a) { return new BlockPos(integer(a.get(0)), integer(a.get(1)), integer(a.get(2))); }
    private static int integer(JsonElement e) { return e.getAsBigDecimal().intValueExact(); }
}
