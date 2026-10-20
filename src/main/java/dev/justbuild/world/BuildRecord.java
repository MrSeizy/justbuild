package dev.justbuild.world;

import dev.justbuild.plan.BuildPlan;
import dev.justbuild.prompt.BuildConstraints;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One AI build: where it is, every version of its plan, and what the world held before the build first touched each
 * position. A version's blocks are derived by running its plan through {@link PlanPipeline}. Server thread only.
 */
public final class BuildRecord {
    /** IDs never repeat after undo/edit; number is only the human-readable position in the original chain. */
    public record Version(String id, String predecessorId, int number, String request, BuildPlan plan, long createdAt,
            Map<BlockPos, BlockState> undoStates) {
        public Version(String request, BuildPlan plan, long createdAt) { this(request, plan, createdAt, Map.of()); }
        public Version(String request, BuildPlan plan, long createdAt, Map<BlockPos, BlockState> undoStates) {
            this(UUID.randomUUID().toString(), null, 0, request, plan, createdAt, undoStates);
        }
        public Version {
            if (!validId(id) || predecessorId != null && !validId(predecessorId) || number < 0) {
                throw new IllegalArgumentException("Invalid build version identity.");
            }
            undoStates = PositionMaps.frozen(undoStates);
        }
        private Version linkedTo(Version previous) {
            return number != 0 ? this : new Version(id, previous == null ? null : previous.id(),
                    previous == null ? 1 : Math.addExact(previous.number(), 1), request, plan, createdAt, undoStates);
        }
        public Version withUndoStates(Map<BlockPos, BlockState> states) {
            return new Version(id, predecessorId, number, request, plan, createdAt, states);
        }
        public static boolean validId(String value) {
            return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        }
    }

    public record Checkpoint(String name, BuildPlan plan, long createdAt) {
    }

    private final String id;
    private final String owner;
    private final ResourceKey<Level> dimension;
    private final BlockPos origin;
    private final Direction facing;
    private final List<Version> versions;
    private final Map<BlockPos, BlockState> baseline;
    private String name;
    private BuildZone zone;
    private boolean savedZone;
    private boolean adopted;
    /** The request exactly as the player typed it (without answers or creative direction); null for other builds. */
    private String typedRequest;
    private final List<Checkpoint> checkpoints = new ArrayList<>();
    private final Map<String, BuildRegion> namedParts = new java.util.LinkedHashMap<>();
    private BuildConstraints constraints = BuildConstraints.NONE;

    public BuildRecord(String id, String owner, ResourceKey<Level> dimension, BlockPos origin, Direction facing,
            List<Version> versions, Map<BlockPos, BlockState> baseline) {
        this.id = id;
        this.owner = owner;
        this.dimension = dimension;
        this.origin = origin;
        this.facing = facing;
        this.versions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Version previous = null;
        for (Version candidate : versions) {
            Version version = candidate.linkedTo(previous);
            if (!ids.add(version.id()) || (previous == null
                    ? version.number() != 1 || version.predecessorId() != null
                    : version.number() <= previous.number() || version.predecessorId() == null)) {
                throw new IllegalArgumentException("Invalid build version chain.");
            }
            this.versions.add(version);
            previous = version;
        }
        if (this.versions.isEmpty()) throw new IllegalArgumentException("A build needs its original version.");
        Map<String, Version> byId = new HashMap<>();
        this.versions.forEach(v -> byId.put(v.id(), v));
        for (Version version : this.versions) {
            Version predecessor = byId.get(version.predecessorId());
            if (predecessor != null && predecessor.number() != version.number() - 1) {
                throw new IllegalArgumentException("Invalid build version predecessor.");
            }
        }
        this.baseline = new HashMap<>(baseline);
    }

    public String id() {
        return id;
    }

    public String owner() {
        return owner;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public BlockPos origin() {
        return origin;
    }

    public Direction facing() {
        return facing;
    }

    public List<Version> versions() {
        return Collections.unmodifiableList(versions);
    }

    public Version current() {
        return versions.get(versions.size() - 1);
    }

    public String title() {
        return name == null ? current().plan().title() : name;
    }

    public String name() { return name; }

    public boolean adopted() { return adopted; }

    public void adopted(boolean value) { adopted = value; }

    public String typedRequest() { return typedRequest; }

    public void typedRequest(String value) { typedRequest = value; }

    public void rename(String value) { name = label(value); }

    public BuildZone zone() { return zone; }

    public boolean hasSavedZone() { return savedZone; }

    public void zone(BuildZone value) {
        if (value != null && !value.dimension().equals(dimension)) {
            throw new IllegalArgumentException("The saved area must be in the build's dimension.");
        }
        zone = value;
        savedZone = true;
    }

    public List<Checkpoint> checkpoints() { return List.copyOf(checkpoints); }

    public Map<String, BuildRegion> namedParts() { return Collections.unmodifiableMap(namedParts); }
    public void savePart(String name, BuildRegion region) {
        String key = label(name);
        if (!dimension.equals(region.dimension())) throw new IllegalArgumentException("The part is in another dimension.");
        String existing = namedParts.keySet().stream().filter(n -> n.equalsIgnoreCase(key)).findFirst().orElse(key);
        if (!namedParts.containsKey(existing) && namedParts.size() >= 32)
            throw new IllegalArgumentException("A build can have at most 32 named parts.");
        namedParts.put(existing, region);
    }
    public void removePart(String name) { namedParts.keySet().removeIf(n -> n.equalsIgnoreCase(name)); }
    public BuildConstraints constraints() { return constraints; }
    public void constraints(BuildConstraints value) { constraints = java.util.Objects.requireNonNull(value); }

    public void addCheckpoint(Checkpoint checkpoint) {
        String label = label(checkpoint.name());
        if (checkpoints.stream().anyMatch(c -> c.name().equalsIgnoreCase(label))) {
            throw new IllegalArgumentException("A checkpoint already has that name. Choose a different name.");
        }
        if (checkpoints.size() >= 32) {
            throw new IllegalArgumentException("This build already has 32 checkpoints.");
        }
        checkpoints.add(new Checkpoint(label, checkpoint.plan(), checkpoint.createdAt()));
    }

    public static String label(String value) {
        String clean = value.strip();
        if (clean.isEmpty() || clean.length() > 60 || clean.chars().anyMatch(c -> Character.isISOControl(c) || c == '§')) {
            throw new IllegalArgumentException("Use a name of 1–60 plain-text characters.");
        }
        return clean;
    }

    public long lastChanged() {
        return current().createdAt();
    }

    public Map<BlockPos, BlockState> baseline() {
        return Collections.unmodifiableMap(baseline);
    }

    public void addVersion(Version version) {
        Version linked = version.linkedTo(current());
        if (!current().id().equals(linked.predecessorId()) || linked.number() != current().number() + 1
                || versions.stream().anyMatch(v -> v.id().equals(linked.id()))) {
            throw new IllegalArgumentException("The new version does not follow the current version.");
        }
        versions.add(linked);
    }

    /** Root removal is forbidden for adopted builds; a retained gap must never masquerade as a predecessor. */
    public boolean canUndoCurrent() {
        return current().predecessorId() == null ? !adopted : versions.size() > 1
                && versions.get(versions.size() - 2).id().equals(current().predecessorId());
    }

    /**
     * Keep the original request/plan, current plan, and every actor's undo target plus its exact predecessor.
     * Survivors keep object identity so retained-plan checks remain valid. Missing predecessors are not synthesized.
     */
    public boolean trimVersions(Set<String> referencedIds) {
        return trimVersions(referencedIds, 0);
    }

    public boolean trimVersions(Set<String> referencedIds, int recent) {
        if (recent < 0 || recent > 32) throw new IllegalArgumentException("Keep at most 32 recent versions.");
        Set<String> keep = new HashSet<>(referencedIds);
        keep.add(versions.getFirst().id());
        keep.add(current().id());
        // Visual history retains recent versions independently of the undo stack's per-actor references.
        versions.stream().skip(Math.max(0, versions.size() - recent)).forEach(v -> keep.add(v.id()));
        Set<String> targets = Set.copyOf(keep);
        for (Version version : versions) {
            if (targets.contains(version.id()) && version.predecessorId() != null) keep.add(version.predecessorId());
        }
        return versions.removeIf(v -> !keep.contains(v.id()));
    }

    public void removeLastVersion() {
        if (versions.size() == 1) throw new IllegalStateException("Remove the build, not its original version.");
        versions.remove(versions.size() - 1);
    }

    /** Remembers what positions held before this build first changed them. */
    public void recordBaseline(List<Placement> previous) {
        for (Placement p : previous) {
            baseline.putIfAbsent(p.pos(), p.state());
        }
    }
}
