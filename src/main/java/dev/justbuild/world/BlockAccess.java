package dev.justbuild.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** The two world operations placement needs; a seam so jobs are testable without a server. */
public interface BlockAccess {
    BlockState get(BlockPos pos);

    /** Places the block; returns false when it was skipped (someone standing there, or a protected block). */
    boolean set(BlockPos pos, BlockState state);

    /** Recomputes neighbor-dependent state (stair corners, fence and pane connections) after bulk placement. */
    void refresh(BlockPos pos);

    /** Whether the position's chunk is loaded; jobs wait instead of loading chunks. */
    default boolean loaded(BlockPos pos) {
        return true;
    }

    /** The footprint captured for a placement, also retained by cancellation's revert job. */
    default BuildZone zone() {
        return null;
    }

    /** Captured edit boundaries and live protected regions, also used by cancellation's revert. */
    default boolean allows(BlockPos pos) { return true; }

    /** Normal placement: clients are updated and neighbor shape updates connect fences and stairs. */
    static BlockAccess of(ServerLevel level) {
        return of(level, false);
    }

    /**
     * {@code quiet} (undo and revert): no neighbor shape updates while placing, so torches and doors resting on removed
     * blocks don't pop off as items; the job's refresh pass fixes shapes afterwards. Solid blocks are never placed
     * where a player, pet, named mob, or villager is.
     */
    static BlockAccess of(ServerLevel level, boolean quiet) {
        return of(level, quiet, null);
    }

    /** With a zone, shape updates are explicit so our placement cannot propagate writes past the footprint. */
    static BlockAccess of(ServerLevel level, boolean quiet, BuildZone zone) {
        return of(level, quiet, zone, null);
    }

    static BlockAccess of(ServerLevel level, boolean quiet, BuildZone zone, java.util.function.Predicate<BlockPos> allowed) {
        return of(level, quiet, zone, allowed, null);
    }

    /**
     * {@code ours}, when given, is what the build itself placed at each position: a block entity there that holds
     * player data, or that the build didn't place, is left alone when the job reaches it (Protection), since a chest
     * may be placed or filled while the job waits in the queue.
     */
    static BlockAccess of(ServerLevel level, boolean quiet, BuildZone zone, java.util.function.Predicate<BlockPos> allowed,
            java.util.Map<BlockPos, BlockState> ours) {
        int flags = quiet || zone != null || allowed != null ? Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE : Block.UPDATE_CLIENTS;
        return new BlockAccess() {
            @Override public boolean allows(BlockPos pos) { return allowed == null || allowed.test(pos); }
            @Override
            public BuildZone zone() {
                return zone;
            }

            @Override
            public boolean loaded(BlockPos pos) {
                return level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
            }

            @Override
            public BlockState get(BlockPos pos) {
                return level.getBlockState(pos);
            }

            @Override
            public boolean set(BlockPos pos, BlockState state) {
                if (!allows(pos)) return false;
                if (zone != null && (!zone.dimension().equals(level.dimension()) || !zone.contains(pos))) {
                    return false;
                }
                if (ours != null) {
                    var entity = level.getBlockEntity(pos);
                    if (entity != null && Protection.isProtected(pos, entity, ours.get(pos), level::getBlockState)) {
                        return false;
                    }
                }
                if (!state.getCollisionShape(level, pos).isEmpty()
                        && !level.getEntitiesOfClass(LivingEntity.class, new AABB(pos), BlockAccess::mustNotBury).isEmpty()) {
                    return false;
                }
                level.setBlock(pos, state, flags);
                return true;
            }

            @Override
            public void refresh(BlockPos pos) {
                if (!allows(pos)) return;
                if (zone != null && (!zone.dimension().equals(level.dimension()) || !zone.contains(pos))) {
                    return;
                }
                BlockState current = level.getBlockState(pos);
                if (current.isAir()) {
                    return;
                }
                BlockState updated = Block.updateFromNeighbourShapes(current, level, pos);
                if (updated != current) {
                    level.setBlock(pos, updated, zone == null && allowed == null ? Block.UPDATE_CLIENTS
                            : Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        };
    }

    /** Creatures a build must not seal into blocks. */
    static boolean mustNotBury(LivingEntity entity) {
        if (entity instanceof Player player) {
            return !player.isSpectator();
        }
        return entity.hasCustomName() || entity instanceof AbstractVillager
                || (entity instanceof OwnableEntity pet && pet.getOwnerReference() != null);
    }
}
