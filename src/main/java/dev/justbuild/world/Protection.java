package dev.justbuild.world;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Keeps builds, edits, and undos from destroying player data: a position holding a block entity is left alone if it
 * is a container with items or an unopened loot table, a written sign, a lectern with a book, a beehive with bees, a
 * campfire with food, or if the build did not place that block itself.
 */
public final class Protection {
    public record Result(LinkedHashMap<BlockPos, BlockState> placements, int kept) {
    }

    private Protection() {
    }

    /**
     * @param ours what this build placed at each position (empty for a new build), so the build may still change its
     *     own empty chests and blank signs
     */
    public static Result filter(Map<BlockPos, BlockState> placements, Map<BlockPos, BlockState> ours,
            Function<BlockPos, BlockEntity> blockEntities, Function<BlockPos, BlockState> current) {
        LinkedHashMap<BlockPos, BlockState> allowed = new LinkedHashMap<>();
        int kept = 0;
        for (Map.Entry<BlockPos, BlockState> entry : placements.entrySet()) {
            BlockPos pos = entry.getKey();
            if (isProtected(pos, blockEntities.apply(pos), ours.get(pos), current)) {
                kept++;
            } else {
                allowed.put(pos, entry.getValue());
            }
        }
        return new Result(allowed, kept);
    }

    /** Non-container block entities that hold something a player put there. */
    static boolean holdsPlayerData(BlockEntity entity) {
        if (entity instanceof SignBlockEntity sign) {
            return hasText(sign.getFrontText()) || hasText(sign.getBackText());
        }
        if (entity instanceof LecternBlockEntity lectern) {
            return lectern.hasBook();
        }
        if (entity instanceof BeehiveBlockEntity hive) {
            return !hive.isEmpty();
        }
        if (entity instanceof CampfireBlockEntity campfire) {
            return campfire.getItems().stream().anyMatch(stack -> !stack.isEmpty());
        }
        return false;
    }

    private static boolean hasText(SignText text) {
        for (var line : text.getMessages(false)) {
            if (!line.getString().isBlank()) {
                return true;
            }
        }
        return false;
    }

    static boolean isProtected(BlockPos pos, BlockEntity entity, BlockState ours, Function<BlockPos, BlockState> current) {
        if (entity == null) {
            return false;
        }
        // Check the loot table before isEmpty(): on a live level isEmpty() would roll the loot.
        if (entity instanceof RandomizableContainer loot && loot.getLootTable() != null) {
            return true;
        }
        if (entity instanceof Container container && !container.isEmpty()) {
            return true;
        }
        if (holdsPlayerData(entity)) {
            return true;
        }
        // Same block as the build placed (its shape may have changed, e.g. two chests joining): still ours.
        return ours == null || current.apply(pos).getBlock() != ours.getBlock();
    }
}
