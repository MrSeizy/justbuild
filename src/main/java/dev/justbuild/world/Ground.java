package dev.justbuild.world;

import java.util.function.IntPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Where a build stands. Under open sky that is the heightmap's surface. Under a roof (the Nether's bedrock ceiling)
 * the heightmap finds the top of the roof, so the floor is found by scanning from a height the player chose.
 */
public final class Ground {
    private Ground() {
    }

    /** The y a block placed on the ground at x, z would occupy; under a ceiling, the floor nearest {@code near}. */
    public static int standingY(ServerLevel level, int x, int z, int near) {
        if (!level.dimensionType().hasCeiling()) {
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        }
        Predicate<BlockState> blocks = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return scan(y -> blocks.test(level.getBlockState(pos.set(x, y, z))), level.getMinY(), level.getMaxY(), near);
    }

    /** Down from {@code near} to the first floor, or up out of the solid block it starts in; {@code near} if neither. */
    static int scan(IntPredicate solid, int minY, int maxY, int near) {
        int y = Math.clamp(near, minY + 1, maxY);
        if (solid.test(y)) {
            while (y < maxY && solid.test(y)) y++;
            return solid.test(y) ? near : y;
        }
        while (y > minY + 1 && !solid.test(y - 1)) y--;
        return solid.test(y - 1) ? y : near;
    }
}
