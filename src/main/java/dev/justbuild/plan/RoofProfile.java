package dev.justbuild.plan;

/**
 * Heights of a sloped roof, course by course from the eaves (k = 0) to the ridge, shared by the rasterizer and the
 * bounds. Each course is a stair; where a course climbs two blocks the cells between it and the course below are an
 * upside-down stair and a full block, so the roof is thick like hand-built ones. A flared roof starts with a slab
 * course and the stairs next to it at the same height, so the eaves curve out. A gambrel (barn) roof climbs two
 * blocks per course for its lower half and one for its upper half.
 */
final class RoofProfile {
    private RoofProfile() {
    }

    /** The y of course {@code k}'s stair, for a roof of {@code layers} courses. */
    static int stairY(int y0, int pitch, boolean flare, boolean gambrel, int layers, int k) {
        int y = y0;
        for (int i = 1; i <= k; i++) {
            y += rise(pitch, flare, gambrel, layers, i);
        }
        return y;
    }

    /** How far course {@code k} (from 1) climbs above the course below it. */
    static int rise(int pitch, boolean flare, boolean gambrel, int layers, int k) {
        if (flare && k == 1) {
            return 0;
        }
        if (gambrel) {
            int first = flare ? 2 : 1;
            return k < first + (layers - first + 1) / 2 ? 2 : 1;
        }
        return pitch;
    }

    /** The most a spire ring climbs: hand-built spires step in every two to four blocks; more reads as boxes. */
    static final int SPIRE_MAX_RISE = 4;

    /**
     * The stair height of each ring of a spire about {@code height} tall (cap included) with {@code layers} rings,
     * like hand-built spires: each ring climbs two to {@link #SPIRE_MAX_RISE} blocks, a little more toward the top,
     * and the peak is a single block one above the last ring (a "finial" makes the needle). A spire can't be taller
     * than its rings allow. With {@code flare} the first two rings share the bottom height.
     */
    static int[] spire(int y0, boolean flare, int layers, int height) {
        int[] s = new int[Math.max(1, layers)];
        int climb = Math.max(0, height - 2); // from the bottom to the peak block; its slab cap sits one above
        if (layers <= 1) {
            s[0] = y0 + Math.min(climb, SPIRE_MAX_RISE);
            return s;
        }
        int first = flare ? 2 : 1;
        s[0] = y0;
        if (flare) {
            s[1] = y0;
        }
        if (layers <= first) {
            return s;
        }
        int middle = layers - 1 - first; // rings between the bottom and the one-block peak
        int total = Math.max(0, climb - 1);
        double weights = 0;
        for (int i = 0; i < middle; i++) {
            weights += 1 + 0.5 * i / Math.max(1, middle - 1);
        }
        double done = 0;
        int placed = 0;
        for (int i = 0; i < middle; i++) {
            done += total * (1 + 0.5 * i / Math.max(1, middle - 1)) / weights;
            int rise = (int) Math.round(done) - placed;
            rise = Math.max(Math.min(2, total / Math.max(1, middle)), Math.min(SPIRE_MAX_RISE, rise));
            placed += rise;
            s[first + i] = s[first + i - 1] + rise;
        }
        s[layers - 1] = s[layers - 2] + 1;
        return s;
    }

    /** A spire's height when the plan gives none: twice its footprint's narrower side. */
    static int spireHeight(Integer height, int sizeX, int sizeZ) {
        return height != null && height > 0 ? height : 2 * Math.min(sizeX, sizeZ);
    }

    /** The highest cell of the roof itself (a thick ridge's cap), not counting a 45° gable's raised rim. */
    static int top(int y0, int pitch, boolean flare, boolean gambrel, int layers) {
        if (pitch == 0) {
            return y0 + Math.max(0, layers - 1) / 2;
        }
        int last = Math.max(0, layers - 1);
        int y = stairY(y0, pitch, flare, gambrel, layers, last);
        return last > 0 && rise(pitch, flare, gambrel, layers, last) >= 2 ? y + 1 : y;
    }
}
