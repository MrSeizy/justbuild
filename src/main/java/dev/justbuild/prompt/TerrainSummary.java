package dev.justbuild.prompt;

import java.util.Map;

/**
 * Ground heights in front of the player, in the plan frame. heights[row][col] is the top solid block's y relative to
 * y = 0 at x = minX + col × step, z = minZ + row × step, or {@link #UNKNOWN} where the chunk is not loaded.
 */
public record TerrainSummary(int step, int minX, int minZ, int[][] heights, Map<String, Integer> surface) {
    public static final int UNKNOWN = Integer.MIN_VALUE;

    public String format() {
        int rows = heights.length;
        int cols = rows == 0 ? 0 : heights[0].length;
        StringBuilder sb = new StringBuilder()
                .append("Terrain (y of the top solid block relative to y = 0, so flat ground at the player's feet is -1; ")
                .append("sampled every ").append(step).append(" blocks; one row per z from ").append(minZ)
                .append(" (far) to ").append(minZ + (rows - 1) * step).append(" (near); columns are x = ").append(minX)
                .append(" to ").append(minX + (cols - 1) * step).append("; ? = not loaded):");
        for (int r = 0; r < rows; r++) {
            sb.append("\nz=").append(minZ + r * step).append(':');
            for (int c = 0; c < cols; c++) {
                sb.append(' ').append(heights[r][c] == UNKNOWN ? "?" : Integer.toString(heights[r][c]));
            }
        }
        if (!surface.isEmpty()) {
            sb.append("\nSurface blocks:");
            surface.forEach((block, count) -> sb.append(' ').append(block).append(' ').append(count).append(','));
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }
}
