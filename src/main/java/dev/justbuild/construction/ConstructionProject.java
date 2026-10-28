package dev.justbuild.construction;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Persisted intent. Completion is always rechecked against the world, never trusted from a counter on disk. */
public final class ConstructionProject {
    public enum Mode { GUIDE, ASSIST, COBBLE }
    public final UUID id, owner;
    public final String title;
    public final ResourceKey<Level> dimension;
    public final Map<BlockPos, BlockState> blueprint;
    public final List<BlockPos> order;
    public final BlockPos min, max;
    public final int totalBlocks;
    public Mode mode;
    public boolean paused = true, currentLayer = true;
    public BlockPos chest;

    public ConstructionProject(UUID id, UUID owner, String title, ResourceKey<Level> dimension,
            Map<BlockPos, BlockState> blueprint, Mode mode) {
        if (title.isBlank() || title.length() > 256 || blueprint.isEmpty()
                || blueprint.size() > dev.justbuild.config.JustBuildConfig.MAX_BLOCKS_LIMIT)
            throw new IllegalArgumentException("Invalid construction blueprint");
        this.id = Objects.requireNonNull(id); this.owner = Objects.requireNonNull(owner);
        this.title = title; this.dimension = Objects.requireNonNull(dimension); this.mode = Objects.requireNonNull(mode);
        var copy = new LinkedHashMap<BlockPos, BlockState>();
        blueprint.forEach((p, s) -> {
            if (Math.abs((long) p.getX()) > 30_000_000 || Math.abs((long) p.getZ()) > 30_000_000 || Math.abs((long) p.getY()) > 4096)
                throw new IllegalArgumentException("Construction position outside world bounds");
            copy.put(p.immutable(), Objects.requireNonNull(s));
        });
        if (new HashSet<>(copy.values()).size() > 4096) throw new IllegalArgumentException("Construction palette exceeds 4096 states");
        totalBlocks = (int) copy.values().stream().filter(s -> !s.isAir()).count();
        if (totalBlocks == 0) throw new IllegalArgumentException("A construction blueprint needs at least one building block");
        this.blueprint = Collections.unmodifiableMap(copy); order = courses(copy);
        min = new BlockPos(copy.keySet().stream().mapToInt(BlockPos::getX).min().orElseThrow(),
                copy.keySet().stream().mapToInt(BlockPos::getY).min().orElseThrow(), copy.keySet().stream().mapToInt(BlockPos::getZ).min().orElseThrow());
        max = new BlockPos(copy.keySet().stream().mapToInt(BlockPos::getX).max().orElseThrow(),
                copy.keySet().stream().mapToInt(BlockPos::getY).max().orElseThrow(), copy.keySet().stream().mapToInt(BlockPos::getZ).max().orElseThrow());
    }
    private static List<BlockPos> courses(Map<BlockPos, BlockState> blocks) {
        // Air inside a hollow building must not steer its wall route. Clear each course, build its shell, then fit details.
        List<List<BlockPos>> phases = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        blocks.forEach((pos, state) -> phases.get(state.isAir() ? 0 : state.isCollisionShapeFullBlock(
                net.minecraft.world.level.EmptyBlockGetter.INSTANCE, pos) ? 1 : 2).add(pos));
        Map<Integer, List<BlockPos>> byHeight = new TreeMap<>();
        for (List<BlockPos> phase : phases) for (BlockPos pos : CourseOrder.order(phase))
            byHeight.computeIfAbsent(pos.getY(), ignored -> new ArrayList<>()).add(pos);
        return byHeight.values().stream().flatMap(List::stream).toList();
    }
}
