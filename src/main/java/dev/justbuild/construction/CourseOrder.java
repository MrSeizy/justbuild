package dev.justbuild.construction;

import java.util.*;
import net.minecraft.core.BlockPos;

/** Bottom-up courses. Follow neighboring cells around a wall before crossing to another component. */
public final class CourseOrder {
    private CourseOrder() { }
    private static final int[][] STEP = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
    public static List<BlockPos> order(Collection<BlockPos> cells) {
        Comparator<BlockPos> start = Comparator.<BlockPos>comparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX);
        var courses = new TreeMap<Integer, NavigableSet<BlockPos>>();
        for (BlockPos p : cells) courses.computeIfAbsent(p.getY(), ignored -> new TreeSet<>(start)).add(p.immutable());
        var result = new ArrayList<BlockPos>(cells.size());
        BlockPos previousCourse = null;
        int direction = 0;
        for (NavigableSet<BlockPos> remaining : courses.values()) {
            BlockPos at = remaining.first();
            if (previousCourse != null) {
                BlockPos above = new BlockPos(previousCourse.getX(), at.getY(), previousCourse.getZ());
                if (remaining.contains(above)) at = above;
            }
            while (!remaining.isEmpty()) {
                remaining.remove(at); result.add(at);
                previousCourse = at;
                BlockPos next = null;
                // Go straight until the corner, then turn right; left turns handle concave wall outlines.
                for (int turn : new int[]{0, 1, 3, 2}) {
                    int d = (direction + turn) % 4;
                    BlockPos candidate = at.offset(STEP[d][0], 0, STEP[d][1]);
                    if (remaining.contains(candidate)) { next = candidate; direction = d; break; }
                }
                if (next == null && !remaining.isEmpty()) {
                    BlockPos from = at;
                    // Bounded search keeps sparse imported blueprints from taking quadratic time.
                    next = remaining.stream().limit(256).min(Comparator.<BlockPos>comparingLong(p ->
                            Math.abs((long) p.getX() - from.getX()) + Math.abs((long) p.getZ() - from.getZ()))
                            .thenComparing(start)).orElseThrow();
                    direction = 0;
                }
                at = next;
            }
        }
        return List.copyOf(result);
    }
}
