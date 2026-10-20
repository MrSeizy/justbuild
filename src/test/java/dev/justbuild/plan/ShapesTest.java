package dev.justbuild.plan;

import static dev.justbuild.plan.Rasterizer.AIR;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShapesTest {
    static final String SOUTH = "oak_stairs[facing=south,half=bottom]";
    static final String NORTH = "oak_stairs[facing=north,half=bottom]";
    static final String EAST = "oak_stairs[facing=east,half=bottom]";
    static final String WEST = "oak_stairs[facing=west,half=bottom]";
    static final String SLAB = "oak_slab[type=bottom]";

    static Map<Cell, String> run(Operation... ops) throws PlanException {
        return run(WorldQuery.NONE, ops);
    }

    static Map<Cell, String> run(WorldQuery world, Operation... ops) throws PlanException {
        return RasterizerTest.specs(Rasterizer.rasterize(List.of(ops), 100_000, world));
    }

    static long count(Map<Cell, String> cells, String spec) {
        return cells.values().stream().filter(spec::equals).count();
    }

    static String at(Map<Cell, String> cells, int x, int y, int z) {
        return cells.get(new Cell(x, y, z));
    }

    static final String SOUTH_UNDER = "oak_stairs[facing=south,half=top]";
    static final String NORTH_UNDER = "oak_stairs[facing=north,half=top]";
    static final String EAST_UNDER = "oak_stairs[facing=east,half=top]";
    static final String TOP_SLAB = "oak_slab[type=top]";

    @Test
    void gableAlongXWithOddDepthHasSlabRidge() throws Exception {
        var cells = run(Operation.roof("oak_stairs", null, new Cell(0, 4, 0), new Cell(4, 4, 2), "gable", null));
        // The roof surface between the gable ends.
        assertEquals(SOUTH, at(cells, 2, 4, 0));
        assertEquals(NORTH, at(cells, 2, 4, 2));
        assertEquals(SLAB, at(cells, 2, 5, 1));
        assertNull(at(cells, 2, 4, 1));
        // Eaves: upside-down stairs under the lowest row.
        assertEquals(SOUTH_UNDER, at(cells, 2, 3, 0));
        assertEquals(NORTH_UNDER, at(cells, 0, 3, 2));
        assertEquals(31, cells.size());
    }

    @Test
    void gableEndsHaveARaisedTwoBlockRim() throws Exception {
        var cells = run(Operation.roof("oak_stairs", null, new Cell(0, 4, 0), new Cell(4, 4, 2), "gable", null));
        for (int x : new int[] {0, 4}) {
            assertEquals(SOUTH, at(cells, x, 5, 0), "rim stair one above the roof");
            assertEquals(NORTH_UNDER, at(cells, x, 4, 0), "upside-down stair under it, facing down the slope");
            assertEquals(NORTH, at(cells, x, 5, 2));
            assertEquals(SLAB, at(cells, x, 6, 1), "rim ridge");
            assertEquals(TOP_SLAB, at(cells, x, 5, 1));
        }
    }

    @Test
    void gableTriangleSitsOneBlockBackUnderTheRim() throws Exception {
        var cells = run(Operation.roof("oak_stairs", "oak_planks", new Cell(0, 0, 0), new Cell(4, 0, 6), "gable", null));
        assertEquals("oak_planks", at(cells, 2, 0, 1));
        assertEquals("oak_planks", at(cells, 1, 0, 1));
        assertEquals("oak_planks", at(cells, 2, 1, 1));
        assertEquals("oak_planks", at(cells, 2, 1, 5));
        assertNull(at(cells, 2, 0, 0), "the outer face stays open behind the rim");
        assertNull(at(cells, 2, 0, 6));
        assertEquals("oak_stairs[facing=east,half=bottom]", at(cells, 0, 1, 0), "rim");
        assertEquals("oak_stairs[facing=west,half=top]", at(cells, 0, 0, 0));
        assertNull(at(cells, 2, 0, 3), "inside the roof is open");
    }

    @Test
    void steepPitchClimbsTwoBlocksPerStep() throws Exception {
        var cells = run(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(4, 0, 2), "gable", null, null, 2));
        assertEquals(SOUTH, at(cells, 2, 0, 0), "the eave course is a lone stair");
        assertEquals(NORTH, at(cells, 2, 0, 2));
        assertEquals("oak_planks", at(cells, 2, 0, 1), "a steep ridge is full blocks");
        assertEquals("oak_planks", at(cells, 2, 1, 1), "rising one block above the last course, not two");
        assertNull(at(cells, 2, 2, 1), "so the peak doesn't stand up like a post");
        assertEquals(SOUTH, at(cells, 0, 0, 0), "a steep rim follows the roof's profile");
        assertNull(at(cells, 0, 1, 0));
        assertEquals("oak_planks", at(cells, 0, 1, 1));
        assertNull(at(cells, 0, 2, 1));
    }

    @Test
    void steepCoursesAreThickLikeHandBuiltRoofs() throws Exception {
        // Each course: an upside-down stair (facing down the slope) level with the course below, a full block, a stair.
        var cells = run(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(8, 0, 4), "gable", null, null, 2));
        assertEquals(SOUTH, at(cells, 4, 0, 0));
        assertEquals(NORTH_UNDER, at(cells, 4, 0, 1));
        assertEquals("oak_planks", at(cells, 4, 1, 1));
        assertEquals(SOUTH, at(cells, 4, 2, 1));
        assertEquals(SOUTH_UNDER, at(cells, 4, 0, 3));
        assertEquals(NORTH, at(cells, 4, 2, 3));
        assertEquals("oak_planks", at(cells, 4, 3, 2), "the peak: one above the last course");
        assertNull(at(cells, 4, 4, 2));
        assertNull(at(cells, 4, 1, 2), "the attic stays open");
        var shallow = run(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(8, 0, 4), "gable", null));
        assertNull(at(shallow, 4, 0, 1), "a 45 degree roof stays one stair thick");
    }

    @Test
    void flareStartsWithASlabCourse() throws Exception {
        var cells = run(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(8, 0, 6), "gable", "x", null, 2,
                null, null, true, null));
        assertEquals(SLAB, at(cells, 4, 0, 0), "slab at the eaves");
        assertEquals(SOUTH, at(cells, 4, 0, 1), "then a stair at the same height");
        assertEquals(NORTH_UNDER, at(cells, 4, 0, 2), "then the steep courses");
        assertEquals(SOUTH, at(cells, 4, 2, 2));
        assertEquals("oak_planks", at(cells, 4, 3, 3), "the peak, one above the last course");
        assertNull(at(cells, 4, 4, 3));
        assertEquals(SOUTH_UNDER, at(cells, 4, -1, 0), "eave stairs still go under the slab");
    }

    @Test
    void gambrelIsSteepBelowAndShallowAbove() throws Exception {
        // 11 wide: 6 courses; the first three climbs are two blocks each, the last two one each.
        var cells = run(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(6, 0, 10), "gambrel", "x", null,
                null, false, false, null, null));
        assertEquals(SOUTH, at(cells, 3, 0, 0));
        assertEquals(SOUTH, at(cells, 3, 2, 1), "steep: two up");
        assertEquals("oak_planks", at(cells, 3, 1, 1));
        assertEquals(SOUTH, at(cells, 3, 4, 2));
        assertEquals(SOUTH, at(cells, 3, 6, 3));
        assertEquals(SOUTH, at(cells, 3, 7, 4), "then 45 degrees: one up");
        assertNull(at(cells, 3, 6, 4), "a 45 degree course stays one stair thick");
        assertEquals(SLAB, at(cells, 3, 8, 5), "slab ridge");
        assertEquals(new Bounds(0, 0, 0, 6, 8, 10), Bounds.of(Operation.roof("oak_stairs", null, new Cell(0, 0, 0),
                new Cell(6, 0, 10), "gambrel", "x", null, null, false, false, null, null)));
    }

    @Test
    void pointedArchMeetsAtAPeak() throws Exception {
        var round = run(Operation.arch("stone_bricks", new Cell(0, 0, 0), 2, 2, "x", false));
        var pointed = run(Operation.arch("stone_bricks", new Cell(0, 0, 0), 2, 2, "x", false, "pointed"));
        assertEquals(4, round.keySet().stream().mapToInt(Cell::y).max().orElseThrow(), "round: h + r");
        assertEquals(6, pointed.keySet().stream().mapToInt(Cell::y).max().orElseThrow(), "pointed: taller");
        assertNotNull(at(pointed, 0, 6, 0), "a one-block tip in the middle");
        assertNull(at(pointed, 1, 6, 0));
        assertNotNull(at(pointed, 1, 5, 0));
        assertNull(at(pointed, 2, 5, 0), "narrowing toward the tip");
        assertEquals(new Bounds(-2, 0, 0, 2, 6, 0), Bounds.of(Operation.arch("stone_bricks", new Cell(0, 0, 0), 2, 2,
                "x", false, "pointed")));
        var wide = run(Operation.arch("stone_bricks", new Cell(0, 0, 0), 3, 0, "x", false, "pointed"));
        assertEquals(java.util.List.of(7, 7, 7, 5, 3, 1), java.util.stream.IntStream.range(0, 6).mapToObj(y ->
                (int) wide.keySet().stream().filter(c -> c.y() == y).count()).toList(), "rows narrow to a point");
    }

    @Test
    void lowPitchClimbsHalfABlockPerCourse() throws Exception {
        // A classical pediment: slab, full block, then a slab one up (on a full block), and so on.
        var cells = run(Operation.roof("quartz_stairs", null, new Cell(0, 0, 0), new Cell(4, 0, 8), "gable", "x", null,
                0, false, false, null, null, null, List.of("quartz_pillar")));
        assertEquals("quartz_slab[type=bottom]", at(cells, 2, 0, 0));
        assertEquals("quartz_block", at(cells, 2, 0, 1));
        assertEquals("quartz_slab[type=bottom]", at(cells, 2, 1, 2));
        assertEquals("quartz_block", at(cells, 2, 0, 2), "under the slab, so the roof is closed");
        assertEquals("quartz_block", at(cells, 2, 1, 3));
        assertEquals("quartz_slab[type=bottom]", at(cells, 2, 2, 4), "ridge");
        assertEquals("quartz_slab[type=bottom]", at(cells, 2, 0, 8), "the other side mirrors it");
        assertEquals("quartz_pillar", at(cells, 0, 2, 4), "a finial replaces the ridge slab, on the full block below");
        assertEquals("quartz_block", at(cells, 0, 1, 4));
        var hip = run(Operation.roof("quartz_stairs", null, new Cell(0, 0, 0), new Cell(4, 0, 4), "hip", null, null, 0));
        assertEquals("quartz_slab[type=bottom]", at(hip, 0, 0, 0));
        assertEquals("quartz_block", at(hip, 1, 0, 1));
        assertEquals("quartz_slab[type=bottom]", at(hip, 2, 1, 2), "peak");
        assertThrows(PlanException.class, () -> PlanParser.parse("""
                {"kind":"build","operations":[{"op":"roof","block":"oak_stairs","from":[0,0,0],"to":[1,0,1],"pitch":3}]}
                """));
    }

    @Test
    void beamRunsAlongTheEavesAndAcrossTheGables() throws Exception {
        var cells = run(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(8, 0, 4), "gable", "x", null,
                null, null, null, null, null, "spruce_log", null));
        assertEquals("spruce_log[axis=x]", at(cells, 4, -1, 0), "instead of eave stairs");
        assertEquals("spruce_log[axis=x]", at(cells, 4, -1, 4));
        assertEquals("spruce_log[axis=z]", at(cells, 1, -1, 2), "tie beam under the gable triangle");
        var planks = run(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(8, 0, 4), "gable", "x", null,
                null, false, null, null, null, "spruce_planks", null));
        assertEquals("spruce_planks", at(planks, 4, -1, 0), "no axis on a block that has none");
        assertNull(at(planks, 0, -1, 2), "no tie beam without a rim, where it would float outside the wall");
    }

    @Test
    void finialStandsOnTheRidgeInPlaceOfItsCap() throws Exception {
        var steep = run(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(8, 0, 4), "gable", "x", null, 2,
                null, null, null, null, null, List.of("dark_oak_fence", "lantern")));
        assertEquals("oak_planks", at(steep, 0, 3, 2), "the full ridge block");
        assertEquals("dark_oak_fence", at(steep, 0, 4, 2), "standing on it");
        assertEquals("lantern", at(steep, 0, 5, 2));
        assertEquals("lantern", at(steep, 8, 5, 2), "both ends");
        var shallow = run(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(8, 0, 4), "gable", "x", null,
                null, null, null, null, null, null, List.of("dark_oak_fence")));
        assertEquals(TOP_SLAB, at(shallow, 0, 2, 2), "a 45 degree rim's peak is a top slab");
        assertEquals("dark_oak_fence", at(shallow, 0, 3, 2), "the finial replaces the slab on it");
        var hip = run(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(6, 0, 4), "hip", null, null, null,
                null, null, null, null, null, List.of("lantern")));
        assertEquals("lantern", at(hip, 2, 2, 2), "each end of a hip's ridge line");
        assertEquals("lantern", at(hip, 4, 2, 2));
        assertEquals(SLAB, at(hip, 3, 2, 2));
    }

    @Test
    void mixCanBlendSeveralBlocks() throws Exception {
        // A path the way builders make them: four stones in about equal shares.
        var cells = run(Operation.fill("stone_bricks", new Cell(0, 0, 0), new Cell(19, 0, 19), false)
                .withMix("mossy_cobblestone", "moss_block", "stone"));
        var counts = new java.util.HashMap<String, Integer>();
        cells.values().forEach(v -> counts.merge(v, 1, Integer::sum));
        assertEquals(4, counts.size());
        for (String b : List.of("mossy_cobblestone", "moss_block", "stone")) {
            assertTrue(counts.get(b) > 50 && counts.get(b) < 110, b + " " + counts.get(b));
        }
        assertTrue(counts.get("stone_bricks") > 120, "the main block keeps two shares: " + counts.get("stone_bricks"));
        var parsed = (PlanResponse.Build) PlanParser.parse("""
                {"kind":"build","title":"t","operations":[
                 {"op":"fill","block":"stone","from":[0,0,0],"to":[1,1,1],"hollow":false,"mix":["andesite","tuff"]}]}
                """);
        assertEquals(List.of("andesite", "tuff"), parsed.plan().operations().get(0).mix());
        assertThrows(PlanException.class, () -> PlanParser.parse("""
                {"kind":"build","operations":[{"op":"fill","block":"stone","from":[0,0,0],"to":[1,0,1],
                 "mix":["a","b","c","d","e"]}]}
                """));
        var rock = run(Operation.ellipsoid("stone", new Cell(0, 0, 0), new Radii(3, 2, 3), "full", false)
                .withMix("andesite", "tuff"));
        assertTrue(rock.containsValue("andesite") && rock.containsValue("tuff") && rock.containsValue("stone"),
                "round shapes take a mix too");
        var written = PlanParser.parse(PlanWriter.write(parsed.plan()));
        assertEquals(List.of("andesite", "tuff"), ((PlanResponse.Build) written).plan().operations().get(0).mix());
    }

    @Test
    void fillMixTexturesTheShellOnly() throws Exception {
        var cells = run(Operation.fill("stone_bricks", new Cell(0, 0, 0), new Cell(6, 4, 6), true)
                .withMix("cracked_stone_bricks"));
        long cracked = cells.values().stream().filter("cracked_stone_bricks"::equals).count();
        assertTrue(cracked > 20 && cracked < 100, "cracked " + cracked);
        assertEquals(AIR, at(cells, 3, 2, 3), "the inside stays cleared");
        var parsed = (PlanResponse.Build) PlanParser.parse("""
                {"kind":"build","title":"t","operations":[
                 {"op":"fill","block":"stone","from":[0,0,0],"to":[1,1,1],"hollow":false,"mix":"andesite"},
                 {"op":"roof","block":"oak_stairs","from":[0,2,0],"to":[1,2,1],"style":"gable","beam":"oak_log",
                  "finial":["oak_fence","lantern"]}]}
                """);
        assertEquals(List.of("andesite"), parsed.plan().operations().get(0).mix(), "a single id still works");
        assertEquals("oak_log", parsed.plan().operations().get(1).beam());
        assertEquals(List.of("oak_fence", "lantern"), parsed.plan().operations().get(1).finial());
        assertThrows(PlanException.class, () -> PlanParser.parse("""
                {"kind":"build","operations":[{"op":"roof","block":"oak_stairs","from":[0,0,0],"to":[1,0,1],
                 "finial":["a","b","c","d","e","f","g","h","i"]}]}
                """));
    }

    @Test
    void mixScattersASecondBlockWithTheSameFacing() throws Exception {
        var cells = run(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(12, 0, 8), "gable", "x", null,
                null, false, false, null, "spruce_stairs"));
        long spruce = cells.values().stream().filter(v -> v.startsWith("spruce_stairs[facing=south")).count();
        long oak = cells.values().stream().filter(v -> v.startsWith("oak_stairs[facing=south")).count();
        assertTrue(spruce > 5 && oak > spruce, "spruce " + spruce + ", oak " + oak);
    }

    @Test
    void edgeBlockFramesTheRimRidgeAndEaves() throws Exception {
        var cells = run(Operation.roof("oak_stairs", "spruce_planks", new Cell(0, 4, 0), new Cell(4, 4, 2), "gable", null,
                "dark_oak_stairs", null));
        assertEquals(SOUTH, at(cells, 2, 4, 0), "the roof surface keeps the roof block");
        assertEquals("dark_oak_stairs[facing=south,half=bottom]", at(cells, 0, 5, 0));
        assertEquals("dark_oak_stairs[facing=south,half=top]", at(cells, 2, 3, 0));
        assertEquals("dark_oak_slab[type=bottom]", at(cells, 2, 5, 1), "ridge cap");
    }

    @Test
    void rimAndEavesCanBeTurnedOffForCleanEdges() throws Exception {
        var plain = run(Operation.roof("oak_stairs", "oak_planks", new Cell(0, 0, 0), new Cell(4, 0, 6), "gable", null,
                null, null, false, false));
        assertEquals("oak_stairs[facing=east,half=bottom]", at(plain, 0, 0, 0), "the roof runs to the very end");
        assertEquals("oak_planks", at(plain, 2, 0, 0), "the triangle is flush with the end");
        assertNull(at(plain, 0, 1, 0), "no rim");
        assertTrue(plain.keySet().stream().noneMatch(c -> c.y() < 0), "no eave stairs");

        var rimOnly = run(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(4, 0, 2), "gable", null, null,
                null, true, false));
        assertEquals(SOUTH, at(rimOnly, 0, 1, 0), "rim still there");
        assertTrue(rimOnly.keySet().stream().noneMatch(c -> c.y() < 0));

        var hip = run(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(4, 0, 4), "hip", null, null, null,
                null, false));
        assertEquals(25, hip.size(), "a hip roof without eave stairs is the plain rings");
    }

    @Test
    void gableWithEvenDepthHasNoRidge() throws Exception {
        var cells = run(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(3, 0, 3), "gable", null));
        assertTrue(cells.values().stream().noneMatch(s -> s.contains("slab")));
        assertEquals(32, cells.size());
    }

    @Test
    void shortGablesHaveNoRim() throws Exception {
        var cells = run(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(1, 0, 2), "gable", "x"));
        assertEquals(SOUTH, at(cells, 0, 0, 0));
        assertNull(at(cells, 0, 1, 0));
    }

    @Test
    void hipRoofRingsFaceInward() throws Exception {
        var cells = run(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(4, 0, 4), "hip", null));
        assertEquals(EAST, at(cells, 0, 0, 2));
        assertEquals(WEST, at(cells, 4, 0, 2));
        assertEquals(SOUTH, at(cells, 2, 1, 1));
        assertEquals(NORTH, at(cells, 2, 1, 3));
        assertEquals(SLAB, at(cells, 2, 2, 2));
        assertEquals(SOUTH_UNDER, at(cells, 2, -1, 0), "eaves");
        assertEquals(EAST_UNDER, at(cells, 0, -1, 2));
        assertEquals(41, cells.size());
    }

    @Test
    void hipCornersTakeTheEdgeBlock() throws Exception {
        var cells = run(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(4, 0, 4), "hip", null,
                "dark_oak_stairs", 2));
        assertEquals("dark_oak_stairs[facing=south,half=bottom]", at(cells, 0, 0, 0), "corner");
        assertEquals("dark_oak_stairs[facing=south,half=bottom]", at(cells, 1, 2, 1), "corner, steep");
        assertEquals("dark_oak_stairs[facing=north,half=top]", at(cells, 1, 0, 1), "under a steep course");
        assertEquals("dark_oak_planks", at(cells, 1, 1, 1));
        assertEquals("oak_stairs[facing=south,half=bottom]", at(cells, 2, 2, 1));
        assertEquals("oak_planks", at(cells, 2, 1, 1));
        assertEquals("oak_planks", at(cells, 2, 4, 2), "a steep peak keeps the roof block");
        assertEquals("dark_oak_slab[type=bottom]", at(cells, 2, 5, 2), "under an edge cap");
    }

    @Test
    void flatRoofUsesSlabForStairsAndBlockOtherwise() throws Exception {
        assertEquals(9, count(run(Operation.roof("stone_bricks", null, new Cell(0, 3, 0), new Cell(2, 3, 2), "flat", null)),
                "stone_bricks"));
        assertEquals(9, count(run(Operation.roof("oak_stairs", null, new Cell(0, 3, 0), new Cell(2, 3, 2), "flat", null)),
                SLAB));
    }

    @Test
    void staircaseClimbsWithSupport() throws Exception {
        var cells = run(Operation.stairs("stone_brick_stairs", "stone_bricks", new Cell(0, 0, 0), new Cell(1, 0, 0), "north", 3));
        assertEquals("stone_brick_stairs[facing=north,half=bottom]", at(cells, 1, 2, -2));
        assertEquals("stone_bricks", at(cells, 1, 1, -2));
        assertEquals("stone_bricks", at(cells, 0, 0, -1));
        assertEquals(12, cells.size());
    }

    @Test
    void solidAndHollowArches() throws Exception {
        var solid = run(Operation.arch("stone", new Cell(0, 0, 0), 2, 1, "x", false));
        assertEquals(18, solid.size());
        assertNull(at(solid, 2, 3, 0));
        var hollow = run(Operation.arch("stone", new Cell(0, 0, 0), 2, 1, "x", true));
        assertEquals(9, count(hollow, "stone"));
        assertEquals(9, count(hollow, AIR));
        assertEquals(AIR, at(hollow, 0, 0, 0));
        assertEquals("stone", at(hollow, 0, 3, 0));
        assertEquals("stone", at(hollow, 2, 0, 0));
        var alongZ = run(Operation.arch("stone", new Cell(0, 0, 0), 2, 1, "z", false));
        assertEquals("stone", at(alongZ, 0, 0, 2));
        assertNull(at(alongZ, 2, 0, 0));
    }

    @Test
    void domesAndBowls() throws Exception {
        var dome = run(Operation.sphere("glass", new Cell(0, 0, 0), 2, "dome", false));
        assertEquals(51, dome.size());
        assertTrue(dome.keySet().stream().allMatch(c -> c.y() >= 0));
        var bowl = run(Operation.sphere("glass", new Cell(0, 0, 0), 2, "bowl", false));
        assertEquals(51, bowl.size());
        assertTrue(bowl.keySet().stream().allMatch(c -> c.y() <= 0));
        assertEquals(81, run(Operation.sphere("glass", new Cell(0, 0, 0), 2, false)).size());
    }

    @Test
    void ellipsoid() throws Exception {
        var cells = run(Operation.ellipsoid("pink_wool", new Cell(0, 0, 0), new Radii(2, 1, 1), "full", false));
        assertEquals(21, cells.size());
        assertNotNull(at(cells, 2, 0, 0));
        assertNull(at(cells, 0, 2, 0));
    }

    @Test
    void voxelsMapRowsAndColumns() throws Exception {
        var cells = run(Operation.voxels(Map.of('P', "pink_wool", 'g', "lime_wool"), new Cell(10, 0, 20),
                List.of(List.of("PP.", "g g"), List.of("P"))));
        assertEquals(5, cells.size());
        assertEquals("pink_wool", at(cells, 11, 0, 20));
        assertEquals("lime_wool", at(cells, 12, 0, 21));
        assertNull(at(cells, 11, 0, 21));
        assertEquals("pink_wool", at(cells, 10, 1, 20));
    }

    @Test
    void replaceMatchesPlanThenWorld() throws Exception {
        WorldQuery stoneAtThree = new FakeWorld() {
            @Override
            public String blockId(Cell cell) {
                return cell.x() == 3 ? "minecraft:stone" : AIR;
            }
        };
        var cells = run(stoneAtThree,
                Operation.fill("stone", new Cell(0, 0, 0), new Cell(2, 0, 0), false),
                Operation.replace("gold_block", "minecraft:stone", new Cell(1, 0, 0), new Cell(4, 0, 0)));
        assertEquals("stone", at(cells, 0, 0, 0));
        assertEquals("gold_block", at(cells, 1, 0, 0));
        assertEquals("gold_block", at(cells, 3, 0, 0));
        assertNull(at(cells, 4, 0, 0));
    }

    @Test
    void foundationStopsAtGroundOrPlannedBlocks() throws Exception {
        WorldQuery groundAtMinusThree = new FakeWorld() {
            @Override
            public boolean isSolid(Cell cell) {
                return cell.y() <= -3;
            }
        };
        var cells = run(groundAtMinusThree,
                Operation.set("stone", new Cell(0, -2, 0)),
                Operation.foundation("cobblestone", new Cell(0, -1, 0), new Cell(1, -1, 0)));
        assertEquals("cobblestone", at(cells, 0, -1, 0));
        assertEquals("stone", at(cells, 0, -2, 0));
        assertEquals("cobblestone", at(cells, 1, -2, 0));
        assertNull(at(cells, 1, -3, 0));
        assertEquals(4, cells.size());
        assertEquals(Rasterizer.FOUNDATION_DEPTH,
                run(Operation.foundation("dirt", new Cell(0, 0, 0), new Cell(0, 0, 0))).size());
    }

    @Test
    void repeatAndMirror() throws Exception {
        var repeated = run(Operation.set("glass", new Cell(0, 1, 0)).withRepeat(new Repeat(3, new Cell(2, 0, 0))));
        assertEquals(List.of(new Cell(0, 1, 0), new Cell(2, 1, 0), new Cell(4, 1, 0)), List.copyOf(repeated.keySet()));

        var mirrored = Rasterizer.rasterize(List.of(
                Operation.set("oak_stairs[facing=east]", new Cell(2, 0, 0)).withMirror(new MirrorPlane("x", 0))), 100);
        assertEquals(new BlockRef("oak_stairs[facing=east]", false, false), mirrored.get(new Cell(2, 0, 0)));
        assertEquals(new BlockRef("oak_stairs[facing=east]", true, false), mirrored.get(new Cell(-2, 0, 0)));

        var even = Rasterizer.rasterize(List.of(
                Operation.set("stone", new Cell(2, 0, 0)).withMirror(new MirrorPlane("x", -0.5))), 100);
        assertTrue(even.containsKey(new Cell(-3, 0, 0)));

        var alongZ = Rasterizer.rasterize(List.of(
                Operation.set("stone", new Cell(0, 0, -3)).withMirror(new MirrorPlane("z", 0))), 100);
        assertTrue(alongZ.get(new Cell(0, 0, 3)).flipZ());

        var both = run(Operation.set("stone", new Cell(1, 0, 0)).withRepeat(new Repeat(2, new Cell(1, 0, 0)))
                .withMirror(new MirrorPlane("x", -0.5)));
        assertEquals(List.of(new Cell(1, 0, 0), new Cell(2, 0, 0), new Cell(-2, 0, 0), new Cell(-3, 0, 0)),
                List.copyOf(both.keySet()));
    }

    abstract static class FakeWorld implements WorldQuery {
        @Override
        public String blockId(Cell cell) {
            return AIR;
        }

        @Override
        public boolean isSolid(Cell cell) {
            return false;
        }
    }
}
