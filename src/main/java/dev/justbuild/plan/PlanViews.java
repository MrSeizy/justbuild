package dev.justbuild.plan;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Text drawings of a plan (top, front, side) so the AI can look at what it designed before it is built. */
public final class PlanViews {
    static final int MAX = 96;
    private static final String SYMBOLS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private PlanViews() {
    }

    public static String render(Map<Cell, BlockRef> cells) {
        Map<Cell, String> solid = new LinkedHashMap<>();
        cells.forEach((cell, ref) -> {
            String id = BlockSpecs.id(ref.spec());
            if (!id.equals(Rasterizer.AIR)) {
                solid.put(cell, id);
            }
        });
        if (solid.isEmpty()) {
            return "The plan places no blocks.";
        }
        // Legend by frequency: the most common block gets 'a'.
        Map<String, Long> counts = solid.values().stream().collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        List<String> ranked = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).toList();
        Map<String, Character> symbol = new HashMap<>();
        StringBuilder legend = new StringBuilder("Legend: ");
        for (int i = 0; i < ranked.size(); i++) {
            char c = i < SYMBOLS.length() ? SYMBOLS.charAt(i) : '?';
            symbol.put(ranked.get(i), c);
            if (i < SYMBOLS.length()) {
                legend.append(c).append(" = ").append(ranked.get(i)).append(", ");
            }
        }
        if (ranked.size() > SYMBOLS.length()) {
            legend.append("? = other blocks, ");
        }
        legend.append(". = empty");

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Cell c : solid.keySet()) {
            minX = Math.min(minX, c.x());
            minY = Math.min(minY, c.y());
            minZ = Math.min(minZ, c.z());
            maxX = Math.max(maxX, c.x());
            maxY = Math.max(maxY, c.y());
            maxZ = Math.max(maxZ, c.z());
        }
        maxX = Math.min(maxX, minX + MAX - 1);
        maxY = Math.min(maxY, minY + MAX - 1);
        maxZ = Math.min(maxZ, minZ + MAX - 1);

        // For each view keep the visible cell: highest y (top), nearest the player = largest z (front),
        // nearest the east = largest x (side).
        Map<Long, int[]> top = new HashMap<>();
        Map<Long, int[]> front = new HashMap<>();
        Map<Long, int[]> side = new HashMap<>();
        for (Map.Entry<Cell, String> e : solid.entrySet()) {
            Cell c = e.getKey();
            if (c.x() > maxX || c.y() > maxY || c.z() > maxZ) {
                continue;
            }
            int s = symbol.get(e.getValue());
            keep(top, key(c.x(), c.z()), c.y(), s);
            keep(front, key(c.x(), c.y()), c.z(), s);
            keep(side, key(c.z(), c.y()), c.x(), s);
        }

        StringBuilder out = new StringBuilder(legend).append("\n\n");
        out.append("Top view (looking down; one row per z from ").append(minZ).append(" (north, far) to ").append(maxZ)
                .append(" (south, near the player); columns are x from ").append(minX).append(" (west) to ").append(maxX)
                .append(" (east)):\n");
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                out.append(symbolAt(top, key(x, z)));
            }
            out.append('\n');
        }
        out.append("\nFront view (as the player sees it, looking north; one row per y from ").append(maxY)
                .append(" (top) to ").append(minY).append("; columns are x from ").append(minX).append(" to ")
                .append(maxX).append("):\n");
        for (int y = maxY; y >= minY; y--) {
            for (int x = minX; x <= maxX; x++) {
                out.append(symbolAt(front, key(x, y)));
            }
            out.append('\n');
        }
        out.append("\nSide view (from the east, looking west; one row per y from ").append(maxY).append(" to ")
                .append(minY).append("; columns are z from ").append(minZ).append(" (north) to ").append(maxZ)
                .append(" (south)):\n");
        for (int y = maxY; y >= minY; y--) {
            for (int z = minZ; z <= maxZ; z++) {
                out.append(symbolAt(side, key(z, y)));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static long key(int a, int b) {
        return ((long) a << 32) ^ (b & 0xffffffffL);
    }

    private static void keep(Map<Long, int[]> view, long key, int depth, int symbol) {
        int[] current = view.get(key);
        if (current == null || depth > current[0]) {
            view.put(key, new int[] {depth, symbol});
        }
    }

    private static char symbolAt(Map<Long, int[]> view, long key) {
        int[] v = view.get(key);
        return v == null ? '.' : (char) v[1];
    }
}
