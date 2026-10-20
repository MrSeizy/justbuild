package dev.justbuild.world;

import dev.justbuild.plan.PlanException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** All build records of the running world, with the current version's blocks cached for lookups. */
public final class BuildRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("JustBuild");

    /** The world access the pipeline needs, so the registry can be tested without a server. */
    public interface Worlds {
        LevelHeightAccessor height(ResourceKey<Level> dimension);

        /** The live block, or null when its chunk isn't loaded (nothing is loaded to compute a build). */
        BlockState live(ResourceKey<Level> dimension, BlockPos pos);
    }

    /** Valid while the version is current and the baseline has not grown (a job finishing adds to it). */
    private record Cached(BuildRecord.Version version, int baselineSize, LinkedHashMap<BlockPos, BlockState> placements,
            BuildRegion bounds) {
    }

    private final Worlds worlds;
    private final Map<String, BuildRecord> records = new LinkedHashMap<>();
    private final Map<String, Cached> cache = new HashMap<>();

    public BuildRegistry(Worlds worlds) {
        this.worlds = worlds;
    }

    public void add(BuildRecord record) {
        records.put(record.id(), record);
    }

    public void remove(String id) {
        records.remove(id);
        cache.remove(id);
    }

    public Optional<BuildRecord> get(String id) {
        return Optional.ofNullable(records.get(id));
    }

    public Collection<BuildRecord> all() {
        return Collections.unmodifiableCollection(records.values());
    }

    public String newId() {
        String id;
        do {
            id = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
        } while (records.containsKey(id));
        return id;
    }

    /** Blocks of the current version (cached). */
    public LinkedHashMap<BlockPos, BlockState> current(BuildRecord record) {
        Cached cached = cache.get(record.id());
        if (cached != null && cached.version() == record.current() && cached.baselineSize() == record.baseline().size()) {
            return cached.placements();
        }
        Computed computed = compute(record, record.current());
        if (computed.complete()) {
            cache.put(record.id(), new Cached(record.current(), record.baseline().size(), computed.placements(), bounds(record, computed.placements())));
        }
        return computed.placements();
    }

    /** Selection bounds share the placement cache, so panel refreshes do not scan every block again. */
    public BuildRegion bounds(BuildRecord record) {
        var placements = current(record);
        Cached cached = cache.get(record.id());
        return cached != null && cached.version() == record.current() && cached.baselineSize() == record.baseline().size()
                ? cached.bounds() : bounds(record, placements);
    }

    private static BuildRegion bounds(BuildRecord record, Map<BlockPos, BlockState> placements) {
        try { return BuildRegion.enclosing(record.dimension(), placements.keySet()); }
        catch (IllegalArgumentException e) { return null; } // Empty or beyond the supported outline range.
    }

    /** Blocks of version {@code count} (1-based); 0 means the build does not exist yet. */
    public LinkedHashMap<BlockPos, BlockState> placements(BuildRecord record, int count) {
        if (count <= 0) {
            return new LinkedHashMap<>();
        }
        if (count == record.versions().size()) {
            return current(record);
        }
        return compute(record, record.versions().get(count - 1)).placements();
    }

    /** The most recently changed build whose current blocks and saved zone include the position. */
    public Optional<BuildRecord> at(ResourceKey<Level> dimension, BlockPos pos) {
        return records.values().stream()
                .filter(r -> r.dimension().equals(dimension))
                .filter(r -> r.zone() == null || r.zone().contains(pos))
                .sorted(Comparator.comparingLong(BuildRecord::lastChanged).reversed())
                .filter(r -> current(r).containsKey(pos))
                .findFirst();
    }

    /** A recorded current build block outside its saved edit zone, still selectable with the wand. */
    public Optional<BuildRecord> atOutsideZone(ResourceKey<Level> dimension, BlockPos pos) {
        return records.values().stream()
                .filter(r -> r.dimension().equals(dimension))
                .filter(r -> r.zone() != null && !r.zone().contains(pos))
                // Only resolve builds that actually touched this block, not every plan outside its zone.
                .filter(r -> r.baseline().containsKey(pos))
                .sorted(Comparator.comparingLong(BuildRecord::lastChanged).reversed())
                .filter(r -> current(r).containsKey(pos))
                .findFirst();
    }

    /** The owner's most recently changed build in the dimension. */
    public Optional<BuildRecord> latest(String owner, ResourceKey<Level> dimension) {
        return records.values().stream()
                .filter(r -> r.owner().equals(owner) && r.dimension().equals(dimension))
                .max(Comparator.comparingLong(BuildRecord::lastChanged));
    }

    /** {@code complete} is false when the plan read the world where its chunk wasn't loaded (seen as air). */
    private record Computed(LinkedHashMap<BlockPos, BlockState> placements, boolean complete) {
    }

    private Computed compute(BuildRecord record, BuildRecord.Version version) {
        Map<BlockPos, BlockState> baseline = record.baseline();
        boolean[] unloaded = {false};
        try {
            LinkedHashMap<BlockPos, BlockState> placements = PlanPipeline.run(version.plan(), record.origin(),
                    record.facing(), worlds.height(record.dimension()), pos -> {
                        if (baseline.containsKey(pos)) return baseline.get(pos);
                        BlockState live = worlds.live(record.dimension(), pos);
                        if (live == null) {
                            unloaded[0] = true;
                            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                        }
                        return live;
                    }, Integer.MAX_VALUE).placements();
            return new Computed(placements, !unloaded[0]);
        } catch (PlanException e) {
            LOGGER.warn("Build {} version no longer resolves: {}", record.id(), e.getMessage());
            return new Computed(new LinkedHashMap<>(), true);
        }
    }
}
