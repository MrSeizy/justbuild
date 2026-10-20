package dev.justbuild.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanParserShapesTest {
    static Operation one(String operation) throws PlanException {
        return ((PlanResponse.Build) PlanParser.parse("{\"kind\":\"build\",\"operations\":[" + operation + "]}"))
                .plan().operations().get(0);
    }

    @org.junit.jupiter.api.Test
    void voxelCharactersMissingFromThePaletteAreLeftUnchanged() throws Exception {
        // One forgotten palette entry used to reject the whole plan (a live Codex gothic chapel, 2026-10-12).
        Operation op = one("{\"op\":\"voxels\",\"palette\":[{\"char\":\"a\",\"block\":\"stone\"}],"
                + "\"from\":[0,0,0],\"layers\":[[\"axa\"]]}");
        assertEquals(java.util.List.of(java.util.List.of("a.a")), op.layers());
        var cells = ShapesTest.run(op);
        assertEquals(2, cells.size());
        assertEquals("stone", cells.get(new Cell(0, 0, 0)));
        assertNull(cells.get(new Cell(1, 0, 0)));
    }

    static void rejects(String expected, String operation) {
        PlanException e = assertThrows(PlanException.class, () -> one(operation));
        assertTrue(e.getMessage().contains(expected), () -> "message was: " + e.getMessage());
    }

    @Test
    void appliesDefaults() throws Exception {
        assertEquals("full", one("{\"op\":\"sphere\",\"block\":\"stone\",\"from\":[0,0,0],\"radius\":2}").style());
        Operation roof = one("{\"op\":\"roof\",\"block\":\"oak_stairs\",\"from\":[0,4,0],\"to\":[6,4,8]}");
        assertEquals("gable", roof.style());
        assertNull(roof.axis());
        assertNull(roof.trim());
        assertNull(roof.edge());
        assertNull(roof.pitch());
        Operation steep = one("{\"op\":\"roof\",\"block\":\"oak_stairs\",\"edge\":\"dark_oak_stairs\",\"pitch\":2,"
                + "\"from\":[0,4,0],\"to\":[6,4,8]}");
        assertEquals("dark_oak_stairs", steep.edge());
        assertEquals(2, steep.pitch());
        assertNull(steep.rim());
        assertNull(steep.eaves());
        Operation clean = one("{\"op\":\"roof\",\"block\":\"quartz_stairs\",\"rim\":false,\"eaves\":false,"
                + "\"from\":[0,4,0],\"to\":[6,4,8]}");
        assertEquals(Boolean.FALSE, clean.rim());
        assertEquals(Boolean.FALSE, clean.eaves());
        Operation arch = one("{\"op\":\"arch\",\"block\":\"air\",\"from\":[0,0,0],\"radius\":1}");
        assertEquals("x", arch.axis());
        assertEquals(0, arch.height());
    }

    @Test
    void parsesShapeFields() throws Exception {
        Operation roof = one("{\"op\":\"roof\",\"block\":\"oak_stairs\",\"trim\":\"oak_planks\",\"from\":[0,4,0],"
                + "\"to\":[6,4,8],\"style\":\"HIP\",\"axis\":\"z\"}");
        assertEquals("hip", roof.style());
        assertEquals("z", roof.axis());
        assertEquals("oak_planks", roof.trim());
        Operation stairs = one("{\"op\":\"stairs\",\"block\":\"stone_stairs\",\"trim\":null,\"from\":[0,0,0],"
                + "\"to\":[2,0,0],\"direction\":\"North\",\"height\":4}");
        assertEquals("north", stairs.direction());
        assertEquals(4, stairs.height());
        Operation e = one("{\"op\":\"ellipsoid\",\"block\":\"pink_wool\",\"from\":[0,5,0],\"radii\":[3,2,4.5],"
                + "\"style\":\"dome\",\"hollow\":true}");
        assertEquals(new Radii(3, 2, 4.5), e.radii());
        assertEquals("dome", e.style());
        assertTrue(e.hollow());
        Operation r = one("{\"op\":\"replace\",\"block\":\"dirt_path\",\"target\":\"grass_block\",\"from\":[0,-1,0],\"to\":[3,-1,3]}");
        assertEquals("grass_block", r.target());
        Operation f = one("{\"op\":\"foundation\",\"block\":\"cobblestone\",\"from\":[0,-1,0],\"to\":[3,-1,3]}");
        assertEquals(OpType.FOUNDATION, f.op());
    }

    @Test
    void parsesVoxelsWithoutBlock() throws Exception {
        Operation v = one("{\"op\":\"voxels\",\"palette\":[{\"char\":\"P\",\"block\":\"pink_wool\"},{\"char\":\"g\",\"block\":\"lime_wool\"}],"
                + "\"from\":[0,0,0],\"layers\":[[\"PP.\",\"g g\"],[\"P\"]]}");
        assertEquals(Map.of('P', "pink_wool", 'g', "lime_wool"), v.palette());
        assertEquals(List.of(List.of("PP.", "g g"), List.of("P")), v.layers());
        assertNull(v.block());
    }

    @Test
    void parsesModifiers() throws Exception {
        Operation op = one("{\"op\":\"set\",\"block\":\"glass\",\"from\":[1,1,0],\"repeat\":{\"count\":3,\"step\":[2,0,0]},"
                + "\"mirror\":{\"axis\":\"x\",\"at\":-0.5}}");
        assertEquals(new Repeat(3, new Cell(2, 0, 0)), op.repeat());
        assertEquals(new MirrorPlane("x", -0.5), op.mirror());
        Operation plain = one("{\"op\":\"set\",\"block\":\"glass\",\"from\":[1,1,0],\"repeat\":null,\"mirror\":null}");
        assertNull(plain.repeat());
        assertNull(plain.mirror());
    }

    @Test
    void validatesShapeFields() {
        rejects("roof needs \"to\"", "{\"op\":\"roof\",\"block\":\"oak_stairs\",\"from\":[0,0,0]}");
        rejects("unknown roof style", "{\"op\":\"roof\",\"block\":\"oak_stairs\",\"from\":[0,0,0],\"to\":[1,0,1],\"style\":\"dome\"}");
        rejects("\"axis\" must be x or z", "{\"op\":\"roof\",\"block\":\"oak_stairs\",\"from\":[0,0,0],\"to\":[1,0,1],\"axis\":\"y\"}");
        rejects("roof \"pitch\" must be 0, 1, or 2", "{\"op\":\"roof\",\"block\":\"oak_stairs\",\"from\":[0,0,0],\"to\":[1,0,1],\"pitch\":3}");
        rejects("stairs needs a \"direction\"", "{\"op\":\"stairs\",\"block\":\"oak_stairs\",\"from\":[0,0,0],\"to\":[1,0,0],\"height\":3}");
        rejects("stairs needs \"height\"", "{\"op\":\"stairs\",\"block\":\"oak_stairs\",\"from\":[0,0,0],\"to\":[1,0,0],\"direction\":\"up\"}".replace("\"up\"", "\"east\""));
        rejects("ellipsoid needs three positive \"radii\"", "{\"op\":\"ellipsoid\",\"block\":\"stone\",\"from\":[0,0,0],\"radii\":[1,0,1]}");
        rejects("unknown sphere style", "{\"op\":\"sphere\",\"block\":\"stone\",\"from\":[0,0,0],\"radius\":2,\"style\":\"cube\"}");
        rejects("replace needs a \"target\"", "{\"op\":\"replace\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[1,1,1]}");
        rejects("voxels needs a \"palette\"", "{\"op\":\"voxels\",\"from\":[0,0,0],\"layers\":[[\"a\"]]}");
        rejects("palette \"char\" must be one character", "{\"op\":\"voxels\",\"palette\":[{\"char\":\"ab\",\"block\":\"stone\"}],\"from\":[0,0,0],\"layers\":[[\"a\"]]}");
        rejects("palette \"char\" must be one character", "{\"op\":\"voxels\",\"palette\":[{\"char\":\".\",\"block\":\"stone\"}],\"from\":[0,0,0],\"layers\":[[\".\"]]}");
        rejects("\"repeat\" needs a positive \"count\"", "{\"op\":\"set\",\"block\":\"stone\",\"from\":[0,0,0],\"repeat\":{\"count\":0,\"step\":[1,0,0]}}");
        rejects("\"mirror\" needs axis x or z", "{\"op\":\"set\",\"block\":\"stone\",\"from\":[0,0,0],\"mirror\":{\"axis\":\"y\",\"at\":0}}");
        rejects("\"mirror.at\" must be a multiple of 0.5", "{\"op\":\"set\",\"block\":\"stone\",\"from\":[0,0,0],\"mirror\":{\"axis\":\"x\",\"at\":0.3}}");
    }

    @Test
    void cleansTextThatGoesIntoChatAndClickCommands() throws Exception {
        var q = (PlanResponse.Question) PlanParser.parse("{\"kind\":\"question\",\"question\":\"§cHow\\nbig?\","
                + "\"options\":[\"Small\\u0007\", \"" + "x".repeat(200) + "\"]}");
        assertEquals("How big?", q.question());
        assertEquals("Small", q.options().get(0));
        assertEquals(80, q.options().get(1).length());
        var plan = ((PlanResponse.Build) PlanParser.parse("{\"kind\":\"build\",\"title\":\"§lBig\\tTower\","
                + "\"operations\":[{\"op\":\"set\",\"block\":\"stone\",\"from\":[0,0,0]}]}")).plan();
        assertEquals("Big Tower", plan.title());
    }

    @Test
    void parsesDecline() throws Exception {
        var declined = assertInstanceOf(PlanResponse.Declined.class, PlanParser.parse(
                "{\"kind\":\"decline\",\"question\":null,\"options\":null,\"title\":null,\"summary\":\"I can't build that.\",\"operations\":null}"));
        assertEquals("I can't build that.", declined.reason());
        assertEquals("No reason given.", ((PlanResponse.Declined) PlanParser.parse("{\"kind\":\"decline\"}")).reason());
    }
}
