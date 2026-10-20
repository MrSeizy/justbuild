package dev.justbuild.world;

import dev.justbuild.plan.Cell;
import dev.justbuild.plan.PlanRotation;
import dev.justbuild.prompt.TerrainSummary;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

/** Samples ground heights in front of the player, in the plan frame, for the prompt; under a roof, the cavern floor. */
public final class TerrainSampler {
    static final int STEP = 2;
    static final int HALF_WIDTH = 24;
    static final int DEPTH = 48;
    static final int SURFACE_KINDS = 5;

    private TerrainSampler() {
    }

    /** Samples z from -48 to 0 (in front of the player) or, when {@code centered}, from -24 to 24 around the origin. */
    public static TerrainSummary sample(ServerLevel level, BlockPos origin, Direction facing, boolean centered) {
        PlanRotation rotation = PlanRotation.forFacing(facing.getName());
        int minZ = centered ? -DEPTH / 2 : -DEPTH;
        int rows = DEPTH / STEP + 1;
        int cols = 2 * HALF_WIDTH / STEP + 1;
        int[][] heights = new int[rows][cols];
        Map<String, Integer> counts = new HashMap<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Cell offset = rotation.apply(new Cell(-HALF_WIDTH + c * STEP, 0, minZ + r * STEP));
                int x = origin.getX() + offset.x();
                int z = origin.getZ() + offset.z();
                if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) {
                    heights[r][c] = TerrainSummary.UNKNOWN;
                    continue;
                }
                int top = Ground.standingY(level, x, z, origin.getY()) - 1;
                heights[r][c] = top - origin.getY();
                String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(x, top, z)).getBlock()).getPath();
                counts.merge(id, 1, Integer::sum);
            }
        }
        Map<String, Integer> surface = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(SURFACE_KINDS)
                .forEach(e -> surface.put(e.getKey(), e.getValue()));
        return new TerrainSummary(STEP, -HALF_WIDTH, minZ, heights, surface);
    }
}
