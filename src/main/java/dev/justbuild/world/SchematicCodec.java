package dev.justbuild.world;

import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.PlanException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.ShortTag;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Data-only Litematica 5–7 and Sponge 2–3 interchange. Litematica uses continuous packed bits (not padded
 * Minecraft chunk storage); Sponge uses x-fastest unsigned varints. No entity NBT is applied or retained.
 */
public final class SchematicCodec {
    public static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_VOLUME = 1_000_000;
    private static final int MAX_PALETTE = 65_536;
    public record Decoded(String name, BuildPlan plan, List<String> warnings) { }
    private SchematicCodec() { }

    public static Decoded read(byte[] bytes, String format, String fallbackName, boolean includeAir) throws IOException, PlanException {
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Schematic exceeds 16 MB compressed.");
        try {
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), new NbtAccounter(64L * 1024 * 1024, 64));
            return decode(root, format, fallbackName, includeAir);
        } catch (RuntimeException e) { throw new IOException("Invalid schematic: " + e.getMessage(), e); }
    }

    static Decoded decode(CompoundTag root, String format, String fallbackName, boolean includeAir) throws IOException, PlanException {
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        String title;
        if (format.equals("litematic")) {
            int version = integer(root, "Version");
            if (version < 5 || version > 7) throw new IOException("Supported Litematica versions: 5, 6, and 7.");
            dataVersion(integer(root, "MinecraftDataVersion"));
            CompoundTag regions = compound(root, "Regions");
            if (regions.isEmpty() || regions.size() > 128) throw new IOException("Use 1–128 schematic regions.");
            int total = 0;
            for (var regionEntry : regions.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                if (!(regionEntry.getValue() instanceof CompoundTag region)) throw new IOException("Invalid region.");
                BlockPos position = pos(compound(region, "Position")), signed = pos(compound(region, "Size"));
                int x = Math.abs(signed.getX()), y = Math.abs(signed.getY()), z = Math.abs(signed.getZ());
                int count = volume(x, y, z); total = Math.addExact(total, count);
                if (total > MAX_VOLUME) throw new IOException("The regions exceed 1,000,000 cells in total.");
                BlockPos min = position.offset(signed.getX() < 0 ? 1 - x : 0, signed.getY() < 0 ? 1 - y : 0, signed.getZ() < 0 ? 1 - z : 0);
                bounds(min, x, y, z);
                ListTag paletteTag = region.getList("BlockStatePalette").orElseThrow(() -> new IOException("Missing block palette."));
                if (paletteTag.isEmpty() || paletteTag.size() > MAX_PALETTE) throw new IOException("Invalid palette size.");
                List<BlockState> palette = new ArrayList<>();
                for (var tag : paletteTag) {
                    if (!(tag instanceof CompoundTag state)) throw new IOException("Invalid palette entry.");
                    String id = state.getString("Name").orElseThrow(() -> new IOException("Missing block name."));
                    if (id.length() > 256) throw new IOException("Block name is too long.");
                    if (state.contains("Properties")) {
                        CompoundTag properties = compound(state, "Properties");
                        if (properties.size() > 32) throw new IOException("Too many block properties.");
                        List<String> pairs = new ArrayList<>();
                        for (String key : properties.keySet().stream().sorted().toList()) {
                            String value = properties.getString(key).orElseThrow(() -> new IOException("Invalid block property."));
                            if (!key.matches("[a-z0-9_]{1,64}") || !value.matches("[a-z0-9_]{1,64}")) throw new IOException("Invalid block property.");
                            pairs.add(key + "=" + value);
                        }
                        if (!pairs.isEmpty()) id += "[" + String.join(",", pairs) + "]";
                    }
                    palette.add(state(id));
                }
                int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
                long[] packed = region.getLongArray("BlockStates").orElseThrow(() -> new IOException("Missing packed block states."));
                if (packed.length != ((long) count * bits + 63) / 64) throw new IOException("Wrong packed block-state length.");
                for (int i = 0; i < count; i++) {
                    int id = unpack(packed, bits, i);
                    if (id >= palette.size()) throw new IOException("Block index is outside its palette.");
                    add(cells, at(min, i, x, z), palette.get(id));
                }
                if (nonempty(region, "TileEntities") || nonempty(region, "Entities") || nonempty(region, "PendingBlockTicks")
                        || nonempty(region, "PendingFluidTicks")) warnings.add("Entity, container, and scheduled-tick data were omitted.");
            }
            title = root.getCompoundOrEmpty("Metadata").getStringOr("Name", fallbackName);
        } else if (format.equals("schem")) {
            CompoundTag schematic = root.contains("Schematic") ? compound(root, "Schematic") : root;
            int version = integer(schematic, "Version");
            if (version != 2 && version != 3) throw new IOException("Supported Sponge schematic versions: 2 and 3.");
            dataVersion(integer(schematic, "DataVersion"));
            int x = dimension(schematic, "Width"), y = dimension(schematic, "Height"), z = dimension(schematic, "Length");
            int count = volume(x, y, z);
            int[] offset = schematic.getIntArray("Offset").orElse(new int[] {0, 0, 0});
            if (offset.length != 3) throw new IOException("Offset must have three coordinates.");
            BlockPos min = new BlockPos(offset[0], offset[1], offset[2]); bounds(min, x, y, z);
            CompoundTag blocks = version == 3 ? compound(schematic, "Blocks") : schematic;
            CompoundTag paletteTag = compound(blocks, "Palette");
            if (paletteTag.isEmpty() || paletteTag.size() > MAX_PALETTE) throw new IOException("Invalid palette size.");
            Map<Integer, BlockState> palette = new LinkedHashMap<>();
            for (String key : paletteTag.keySet()) {
                int index = integer(paletteTag, key);
                if (index < 0 || index >= MAX_PALETTE || palette.put(index, state(key)) != null) throw new IOException("Invalid or repeated palette index.");
            }
            byte[] data = blocks.getByteArray(version == 3 ? "Data" : "BlockData").orElseThrow(() -> new IOException("Missing block data."));
            if (data.length > count * 3L) throw new IOException("Oversized block data.");
            int cursor = 0;
            for (int i = 0; i < count; i++) {
                int value = 0, shift = 0;
                while (true) {
                    if (cursor >= data.length || shift > 14) throw new IOException("Invalid block-state varint.");
                    int next = data[cursor++] & 255; value |= (next & 127) << shift;
                    if ((next & 128) == 0) break;
                    shift += 7;
                }
                BlockState state = palette.get(value);
                if (state == null) throw new IOException("Block index is outside its palette.");
                add(cells, at(min, i, x, z), state);
            }
            if (cursor != data.length) throw new IOException("Extra block-state data.");
            if (nonempty(blocks, "BlockEntities") || nonempty(schematic, "Entities") || nonempty(schematic, "Biomes"))
                warnings.add("Entity, container, and biome data were omitted.");
            title = schematic.getCompoundOrEmpty("Metadata").getStringOr("Name", fallbackName);
        } else throw new IOException("Choose litematic or schem.");
        if (!includeAir) {
            int before = cells.size(); cells.values().removeIf(BlockState::isAir);
            if (cells.size() != before) warnings.add("Air blocks were skipped. Enable Include air when importing to preserve clearing.");
        }
        if (cells.isEmpty()) throw new IOException("The schematic has no blocks to import.");
        // Bound the combined extent too: individually small regions may be far apart.
        box(cells);
        return new Decoded(label(title, fallbackName), PlanSnapshot.capture(label(title, fallbackName), BlockPos.ZERO,
                Direction.NORTH, cells), warnings.stream().distinct().toList());
    }

    public static byte[] write(BuildPlan plan, String name, String format) throws IOException, PlanException {
        var result = PlanPipeline.run(plan, BlockPos.ZERO, Direction.NORTH, LevelHeightAccessor.create(-4096, 8192),
                p -> Blocks.AIR.defaultBlockState(), MAX_VOLUME);
        if (!result.invalid().isEmpty() || result.outsideWorld() > 0) throw new IOException("This design has unavailable blocks or unsupported coordinates.");
        CompoundTag root = encode(result.placements(), name, format);
        var bytes = new ByteArrayOutputStream(); NbtIo.writeCompressed(root, bytes);
        if (bytes.size() > MAX_FILE_BYTES) throw new IOException("Schematic exceeds 16 MB compressed.");
        return bytes.toByteArray();
    }

    static CompoundTag encode(Map<BlockPos, BlockState> cells, String name, String format) throws IOException {
        BuildRegion box = box(cells);
        int x = box.max().getX() - box.min().getX() + 1, y = box.max().getY() - box.min().getY() + 1, z = box.max().getZ() - box.min().getZ() + 1;
        int count = volume(x, y, z);
        Map<BlockState, Integer> ids = new LinkedHashMap<>(); ids.put(Blocks.AIR.defaultBlockState(), 0);
        ids.put(Blocks.STRUCTURE_VOID.defaultBlockState(), 1);
        cells.values().stream().distinct().sorted(java.util.Comparator.comparing(BlockStateParser::serialize)).forEach(s -> ids.putIfAbsent(s, ids.size()));
        if (ids.size() > MAX_PALETTE) throw new IOException("Too many block states.");
        int[] indices = new int[count];
        for (int i = 0; i < count; i++) indices[i] = ids.get(cells.getOrDefault(at(box.min(), i, x, z), Blocks.STRUCTURE_VOID.defaultBlockState()));
        CompoundTag metadata = new CompoundTag(); metadata.putString("Name", name); metadata.putString("Author", "JustBuild");
        metadata.putString("Description", "Block states only; no entities or block-entity data.");
        CompoundTag root = new CompoundTag();
        if (format.equals("litematic")) {
            root.putInt("Version", 7); root.putInt("SubVersion", 1); root.putInt("MinecraftDataVersion", currentDataVersion());
            metadata.putInt("RegionCount", 1); metadata.putLong("TimeCreated", System.currentTimeMillis()); metadata.putLong("TimeModified", System.currentTimeMillis());
            metadata.putLong("TotalVolume", count); metadata.putLong("TotalBlocks", cells.values().stream().filter(s -> !s.isAir()).count());
            metadata.put("EnclosingSize", position(new BlockPos(x, y, z))); root.put("Metadata", metadata);
            CompoundTag region = new CompoundTag(); region.put("Position", position(box.min())); region.put("Size", position(new BlockPos(x, y, z)));
            ListTag palette = new ListTag(); ids.keySet().forEach(s -> palette.add(NbtUtils.writeBlockState(s))); region.put("BlockStatePalette", palette);
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(ids.size() - 1));
            long[] packed = new long[(int) (((long) count * bits + 63) / 64)];
            for (int i = 0; i < count; i++) pack(packed, bits, i, indices[i]);
            region.putLongArray("BlockStates", packed); region.put("TileEntities", new ListTag()); region.put("Entities", new ListTag());
            region.put("PendingBlockTicks", new ListTag()); region.put("PendingFluidTicks", new ListTag());
            CompoundTag regions = new CompoundTag(); regions.put("Build", region); root.put("Regions", regions);
        } else if (format.equals("schem")) {
            CompoundTag schematic = new CompoundTag(); schematic.putInt("Version", 3); schematic.putInt("DataVersion", currentDataVersion());
            schematic.putShort("Width", (short) x); schematic.putShort("Height", (short) y); schematic.putShort("Length", (short) z);
            schematic.putIntArray("Offset", new int[] {box.min().getX(), box.min().getY(), box.min().getZ()});
            schematic.put("Metadata", metadata); CompoundTag blocks = new CompoundTag(), palette = new CompoundTag();
            ids.forEach((state, id) -> palette.putInt(BlockStateParser.serialize(state), id)); blocks.put("Palette", palette);
            var data = new ByteArrayOutputStream();
            for (int index : indices) { int value = index; while ((value & ~127) != 0) { data.write((value & 127) | 128); value >>>= 7; } data.write(value); }
            blocks.putByteArray("Data", data.toByteArray()); blocks.put("BlockEntities", new ListTag()); schematic.put("Blocks", blocks);
            schematic.put("Entities", new ListTag()); root.put("Schematic", schematic);
        } else throw new IOException("Choose litematic or schem.");
        return root;
    }

    private static BuildRegion box(Map<BlockPos, BlockState> cells) throws IOException {
        if (cells.isEmpty() || cells.size() > MAX_VOLUME) throw new IOException("Use 1–1,000,000 known blocks.");
        try { return BuildRegion.enclosing(net.minecraft.world.level.Level.OVERWORLD, cells.keySet()); }
        catch (IllegalArgumentException e) { throw new IOException("Schematic extent must be at most 256 blocks on each axis.", e); }
    }
    private static int volume(int x, int y, int z) throws IOException {
        if (x < 1 || y < 1 || z < 1 || x > 256 || y > 256 || z > 256 || (long) x * y * z > MAX_VOLUME)
            throw new IOException("Schematic region must be at most 256 per axis and 1,000,000 cells.");
        return x * y * z;
    }
    private static void bounds(BlockPos min, int x, int y, int z) throws IOException {
        for (long n : new long[] {min.getX(), min.getY(), min.getZ(), (long) min.getX() + x - 1,
                (long) min.getY() + y - 1, (long) min.getZ() + z - 1}) if (Math.abs(n) > 4096) throw new IOException("Schematic offsets must stay within ±4096.");
    }
    private static int dimension(CompoundTag tag, String key) throws IOException {
        if (!(tag.get(key) instanceof ShortTag)) throw new IOException("Missing short field: " + key);
        return Short.toUnsignedInt(tag.getShortOr(key, (short) 0));
    }
    private static int integer(CompoundTag tag, String key) throws IOException {
        if (!(tag.get(key) instanceof IntTag)) throw new IOException("Missing integer field: " + key);
        return tag.getIntOr(key, 0);
    }
    private static CompoundTag compound(CompoundTag tag, String key) throws IOException {
        return tag.getCompound(key).orElseThrow(() -> new IOException("Missing compound: " + key));
    }
    private static BlockPos pos(CompoundTag tag) throws IOException { return new BlockPos(integer(tag, "x"), integer(tag, "y"), integer(tag, "z")); }
    private static CompoundTag position(BlockPos p) { CompoundTag t = new CompoundTag(); t.putInt("x", p.getX()); t.putInt("y", p.getY()); t.putInt("z", p.getZ()); return t; }
    private static BlockPos at(BlockPos min, int index, int x, int z) { return min.offset(index % x, index / (x * z), index / x % z); }
    private static BlockState state(String spec) throws IOException {
        if (spec.length() > 4096) throw new IOException("Block state is too long.");
        return BlockResolver.parse(spec).orElseThrow(() -> new IOException("Unknown block state: " + spec));
    }
    private static void add(Map<BlockPos, BlockState> cells, BlockPos pos, BlockState state) throws IOException {
        if (state.is(Blocks.STRUCTURE_VOID)) return;
        BlockState prior = cells.putIfAbsent(pos, state);
        if (prior != null && !prior.equals(state)) throw new IOException("Overlapping regions disagree at " + pos.toShortString() + ".");
    }
    private static boolean nonempty(CompoundTag tag, String key) { var value = tag.get(key); return value instanceof ListTag list ? !list.isEmpty() : value instanceof CompoundTag c && !c.isEmpty(); }
    private static int currentDataVersion() { return SharedConstants.getCurrentVersion().dataVersion().version(); }
    private static void dataVersion(int version) throws IOException { if (version < 1451 || version > currentDataVersion()) throw new IOException("Schematic uses an unsupported Minecraft data version: " + version); }
    private static String label(String title, String fallback) {
        String text = title.replaceAll("[\\p{Cntrl}§]", "").strip(); if (text.isBlank()) text = fallback;
        return BuildRecord.label(text.length() > 60 ? text.substring(0, 60) : text);
    }
    static int unpack(long[] values, int bits, int index) {
        long offset = (long) index * bits; int word = (int) (offset >>> 6), shift = (int) (offset & 63);
        long value = values[word] >>> shift;
        if (shift + bits > 64) value |= values[word + 1] << (64 - shift);
        return (int) (value & ((1L << bits) - 1));
    }
    static void pack(long[] values, int bits, int index, int value) {
        long offset = (long) index * bits; int word = (int) (offset >>> 6), shift = (int) (offset & 63);
        values[word] |= (long) value << shift;
        if (shift + bits > 64) values[word + 1] |= (long) value >>> (64 - shift);
    }
}
