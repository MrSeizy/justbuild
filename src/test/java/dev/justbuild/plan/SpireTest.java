package dev.justbuild.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class SpireTest {
    static Operation spire(String extra) throws Exception {
        var build = (PlanResponse.Build) PlanParser.parse("{\"kind\":\"build\",\"title\":\"t\",\"operations\":["
                + "{\"op\":\"roof\",\"block\":\"deepslate_tile_stairs\",\"from\":[0,10,0],\"to\":[8,10,8],"
                + "\"style\":\"spire\"" + extra + "}]}");
        return build.plan().operations().get(0);
    }

    /** The highest block in each ring (distance in from the edge) along the middle of the north face. */
    static Map<Integer, Integer> ringTops(Map<Cell, String> cells) {
        Map<Integer, Integer> tops = new TreeMap<>();
        cells.forEach((c, b) -> {
            if (c.x() == 4 && c.z() <= 4 && c.y() >= 10) {
                tops.merge(c.z(), c.y(), Math::max);
            }
        });
        return tops;
    }

    @Test
    void defaultSpireClimbsFourPerRingToAOneBlockPeak() throws Exception {
        Operation op = spire("");
        var cells = ShapesTest.run(op);
        int top = cells.keySet().stream().mapToInt(Cell::y).max().orElseThrow();
        assertEquals(24, top, "a 9-wide spire: four rings of four, a peak block, and a cap");
        assertEquals("deepslate_tile_slab[type=bottom]", cells.get(new Cell(4, top, 4)), "capped at the tip");
        assertEquals(Map.of(0, 10, 1, 14, 2, 18, 3, 22, 4, 24), ringTops(cells), "the center counts its cap");
        assertNull(cells.get(new Cell(4, 14, 4)), "hollow inside");
        assertEquals(new Bounds(0, 9, 0, 8, 24, 8), Bounds.of(op), "eaves below, cap on top");
    }

    @Test
    void shorterSpiresSteepenTowardTheTip() throws Exception {
        var tops = ringTops(ShapesTest.run(spire(",\"height\":12")));
        assertEquals(Map.of(0, 10, 1, 12, 2, 15, 3, 19, 4, 21), tops, "climbs 2, 3, 4, then the peak and its cap");
    }

    @Test
    void heightFlareEdgeAndFinial() throws Exception {
        var tall = ShapesTest.run(spire(",\"height\":30"));
        assertEquals(24, tall.keySet().stream().mapToInt(Cell::y).max().orElseThrow(), "no ring climbs more than four");
        var styled = spire(",\"height\":20,\"flare\":true,\"edge\":\"polished_blackstone_brick_stairs\","
                + "\"finial\":[\"deepslate_brick_wall\",\"deepslate_brick_wall\",\"end_rod\"]");
        var cells = ShapesTest.run(styled);
        assertEquals("deepslate_tile_slab[type=bottom]", cells.get(new Cell(4, 10, 0)), "flared: a slab course first");
        assertTrue(cells.get(new Cell(0, 10, 0)).startsWith("polished_blackstone_brick"), "corners take the edge");
        assertEquals("deepslate_tiles", cells.get(new Cell(4, 19, 4)), "a full peak block");
        assertEquals("deepslate_brick_wall", cells.get(new Cell(4, 20, 4)), "the needle replaces the cap");
        assertEquals("end_rod", cells.get(new Cell(4, 22, 4)));
        assertEquals(new Bounds(0, 9, 0, 8, 23, 8), Bounds.of(styled));
    }

    @Test
    void spireHeightsAndWritingBack() throws Exception {
        for (boolean flare : new boolean[] {false, true}) {
            for (int rings = 3; rings <= 8; rings++) {
                for (int height = 4; height <= 40; height += 6) {
                    int[] s = RoofProfile.spire(0, flare, rings, height);
                    String shown = java.util.Arrays.toString(s) + " for " + rings + " rings, height " + height;
                    assertEquals(0, s[0]);
                    assertEquals(1, s[rings - 1] - s[rings - 2], "a one-block peak: " + shown);
                    assertTrue(s[rings - 1] + 2 <= Math.max(height, 3), "never taller than asked: " + shown);
                    for (int k = (flare ? 2 : 1); k < rings - 1; k++) {
                        int rise = s[k] - s[k - 1];
                        // A spire too short for its rings has a flat ring or two; none climbs more than the max.
                        assertTrue(rise >= (height >= 3 * rings ? 2 : 0) && rise <= RoofProfile.SPIRE_MAX_RISE, shown);
                        if (k > (flare ? 2 : 1) && height >= 3 * rings) {
                            assertTrue(rise >= s[k - 1] - s[k - 2], "steeper toward the tip: " + shown);
                        }
                    }
                }
            }
        }
        assertArrayEquals(new int[] {3}, RoofProfile.spire(0, false, 1, 5), "a one-ring spire is a short column");
        assertThrows(PlanException.class, () -> spire(",\"height\":1"));
        Operation op = spire(",\"height\":24");
        var written = (PlanResponse.Build) PlanParser.parse(PlanWriter.write(new BuildPlan("t", "", java.util.List.of(op))));
        assertEquals(24, written.plan().operations().get(0).height());
        Operation plain = spire("");
        assertNull(((PlanResponse.Build) PlanParser.parse(PlanWriter.write(new BuildPlan("t", "",
                java.util.List.of(plain))))).plan().operations().get(0).height(), "no height stays no height");
    }
}
