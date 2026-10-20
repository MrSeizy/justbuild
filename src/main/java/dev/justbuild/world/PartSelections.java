package dev.justbuild.world;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Small, explainable selection helpers. The highlighted box is always the actual edit boundary. */
public final class PartSelections {
    private PartSelections() { }

    public static BuildRegion roof(ResourceKey<Level> dimension, Map<BlockPos, BlockState> blocks) {
        List<BlockPos> solid = blocks.entrySet().stream().filter(e -> !e.getValue().isAir()).map(Map.Entry::getKey).toList();
        BuildRegion bounds = BuildRegion.enclosing(dimension, solid);
        int middle = bounds.min().getY() + (bounds.max().getY() - bounds.min().getY()) / 2;
        int roofY = blocks.entrySet().stream().filter(e -> e.getKey().getY() >= middle
                        && (e.getValue().getBlock() instanceof StairBlock || e.getValue().getBlock() instanceof SlabBlock))
                .mapToInt(e -> e.getKey().getY()).min()
                .orElse(bounds.min().getY() + 2 * (bounds.max().getY() - bounds.min().getY()) / 3);
        return new BuildRegion(dimension, new BlockPos(bounds.min().getX(), roofY, bounds.min().getZ()), bounds.max());
    }

    public static BuildRegion wall(BuildRegion bounds, BlockPos pointed, Direction face) {
        if (!bounds.contains(pointed)) throw new IllegalArgumentException("Point at a wall of the selected build.");
        if (face.getAxis() == Direction.Axis.Y) throw new IllegalArgumentException("Point at the side of a wall.");
        return face.getAxis() == Direction.Axis.X
                ? new BuildRegion(bounds.dimension(), new BlockPos(pointed.getX(), bounds.min().getY(), bounds.min().getZ()),
                        new BlockPos(pointed.getX(), bounds.max().getY(), bounds.max().getZ()))
                : new BuildRegion(bounds.dimension(), new BlockPos(bounds.min().getX(), bounds.min().getY(), pointed.getZ()),
                        new BlockPos(bounds.max().getX(), bounds.max().getY(), pointed.getZ()));
    }

    /** Find the enclosed air space next to the clicked surface, then include its one-block shell. */
    public static BuildRegion room(BuildRegion bounds, BlockPos inside, Function<BlockPos, BlockState> live) {
        if (!bounds.contains(inside) || !live.apply(inside).isAir()) {
            throw new IllegalArgumentException("Stand inside the room and point at an inside wall, floor, or ceiling.");
        }
        Set<BlockPos> air = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(inside.immutable()); air.add(inside.immutable());
        while (!queue.isEmpty()) {
            BlockPos p = queue.removeFirst();
            for (Direction d : Direction.values()) {
                BlockPos next = p.relative(d);
                if (!live.apply(next).isAir()) continue;
                if (!bounds.contains(next) || air.size() >= 4096) {
                    throw new IllegalArgumentException("That room opens outside or is too large. Use two part corners instead.");
                }
                if (air.add(next)) queue.addLast(next);
            }
        }
        BuildRegion room = BuildRegion.enclosing(bounds.dimension(), air);
        return BuildRegion.between(bounds.dimension(), room.min().offset(-1, -1, -1), room.max().offset(1, 1, 1));
    }
}
