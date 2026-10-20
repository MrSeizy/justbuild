package dev.justbuild.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.justbuild.plan.BuildPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;

/** Compact top views generated from actual design blocks, with a bounded sixteen-color palette. */
public final class DesignThumbnail {
    public static final int SIZE = 16;
    public record Image(List<Integer> colors, String pixels) {
        public static final Image EMPTY = new Image(List.of(), ".".repeat(SIZE * SIZE));
        public Image {
            colors = List.copyOf(colors);
            if (colors.size() > 16 || pixels.length() != SIZE * SIZE || colors.stream().anyMatch(c -> c < 0 || c > 0xffffff)) {
                throw new IllegalArgumentException("Invalid design thumbnail.");
            }
            for (char c : pixels.toCharArray()) if (c != '.' && (Character.digit(c, 16) < 0 || Character.digit(c, 16) >= colors.size())) {
                throw new IllegalArgumentException("Invalid design thumbnail pixel.");
            }
        }
        public JsonObject toJson() {
            JsonObject o = new JsonObject(); JsonArray palette = new JsonArray();
            colors.forEach(palette::add); o.add("colors", palette); o.addProperty("pixels", pixels); return o;
        }
        public static Image parse(JsonObject o) {
            List<Integer> colors = new ArrayList<>();
            if (o.getAsJsonArray("colors").size() > 16) throw new IllegalArgumentException("Thumbnail palette is too large.");
            o.getAsJsonArray("colors").forEach(e -> colors.add(e.getAsBigDecimal().intValueExact()));
            return new Image(colors, o.get("pixels").getAsString());
        }
    }
    private DesignThumbnail() { }

    public static Image create(BuildPlan plan) {
        try {
            var blocks = PlanPipeline.run(plan, BlockPos.ZERO, Direction.NORTH, LevelHeightAccessor.create(-1024, 2048),
                    p -> Blocks.AIR.defaultBlockState(), dev.justbuild.config.JustBuildConfig.MAX_BLOCKS_LIMIT).placements();
            List<BlockPos> solid = blocks.entrySet().stream().filter(e -> !e.getValue().isAir()).map(Map.Entry::getKey).toList();
            if (solid.isEmpty()) return Image.EMPTY;
            int x0 = solid.stream().mapToInt(BlockPos::getX).min().orElse(0), x1 = solid.stream().mapToInt(BlockPos::getX).max().orElse(0);
            int z0 = solid.stream().mapToInt(BlockPos::getZ).min().orElse(0), z1 = solid.stream().mapToInt(BlockPos::getZ).max().orElse(0);
            int span = Math.max(x1 - x0 + 1, z1 - z0 + 1);
            int[] heights = new int[SIZE * SIZE], rgb = new int[SIZE * SIZE];
            java.util.Arrays.fill(heights, Integer.MIN_VALUE);
            java.util.Arrays.fill(rgb, -1);
            for (BlockPos p : solid) {
                int column = p.getX() - x0 + (span - (x1 - x0 + 1)) / 2;
                int row = p.getZ() - z0 + (span - (z1 - z0 + 1)) / 2;
                int fromX = column * SIZE / span, toX = Math.max(fromX, (column + 1) * SIZE / span - 1);
                int fromZ = row * SIZE / span, toZ = Math.max(fromZ, (row + 1) * SIZE / span - 1);
                for (int z = fromZ; z <= Math.min(SIZE - 1, toZ); z++) for (int x = fromX; x <= Math.min(SIZE - 1, toX); x++) {
                    int i = z * SIZE + x;
                    if (p.getY() >= heights[i]) {
                        heights[i] = p.getY();
                        int color = blocks.get(p).getMapColor(EmptyBlockGetter.INSTANCE, p).col;
                        rgb[i] = color == 0 ? 0x888888 : color & 0xffffff;
                    }
                }
            }
            Map<Integer, Integer> counts = new HashMap<>();
            for (int c : rgb) if (c >= 0) counts.merge(c, 1, Integer::sum);
            List<Integer> colors = counts.entrySet().stream().sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey())).limit(16).map(Map.Entry::getKey).toList();
            StringBuilder pixels = new StringBuilder();
            for (int c : rgb) {
                if (c < 0) { pixels.append('.'); continue; }
                int closest = colors.stream().min(Comparator.comparingInt(v -> distance(c, v))).orElse(c);
                pixels.append(Character.forDigit(colors.indexOf(closest), 16));
            }
            return new Image(colors, pixels.toString());
        } catch (Exception e) { return Image.EMPTY; }
    }

    private static int distance(int a, int b) {
        int r = (a >> 16) - (b >> 16), g = (a >> 8 & 255) - (b >> 8 & 255), bl = (a & 255) - (b & 255);
        return r * r + g * g + bl * bl;
    }
}
