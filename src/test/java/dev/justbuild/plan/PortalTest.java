package dev.justbuild.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PortalTest {
    static final String OBSIDIAN = "minecraft:obsidian";

    static Map<Cell, String> plan(String ops) throws Exception {
        var build = (PlanResponse.Build) PlanParser.parse("{\"kind\":\"build\",\"title\":\"t\",\"operations\":[" + ops + "]}");
        return ShapesTest.run(build.plan().operations().toArray(Operation[]::new));
    }

    @Test
    void netherPortalGetsAnExactObsidianFrameAndLitOpening() throws Exception {
        // A 3 wide, 4 tall opening facing north/south (one z).
        var cells = plan("{\"op\":\"portal\",\"style\":\"nether\",\"from\":[0,1,-5],\"to\":[2,4,-5]}");
        for (int x = -1; x <= 3; x++) {
            assertEquals(OBSIDIAN, cells.get(new Cell(x, 0, -5)), "bottom " + x);
            assertEquals(OBSIDIAN, cells.get(new Cell(x, 5, -5)), "top " + x);
        }
        for (int y = 0; y <= 5; y++) {
            assertEquals(OBSIDIAN, cells.get(new Cell(-1, y, -5)), "left " + y);
            assertEquals(OBSIDIAN, cells.get(new Cell(3, y, -5)), "right " + y);
        }
        for (int x = 0; x <= 2; x++) {
            for (int y = 1; y <= 4; y++) {
                assertEquals("minecraft:nether_portal[axis=x]", cells.get(new Cell(x, y, -5)));
            }
        }
        assertEquals(5 * 6, cells.size(), "nothing outside the frame");
        var alongZ = plan("{\"op\":\"portal\",\"style\":\"nether\",\"from\":[4,0,-3],\"to\":[4,2,-4]}");
        assertEquals("minecraft:nether_portal[axis=z]", alongZ.get(new Cell(4, 1, -3)), "a frame along z gets axis z");
        assertEquals(OBSIDIAN, alongZ.get(new Cell(4, 1, -5)));
        var unlit = plan("{\"op\":\"portal\",\"style\":\"nether\",\"from\":[0,1,0],\"to\":[1,3,0],\"hollow\":true}");
        assertEquals(Rasterizer.AIR, unlit.get(new Cell(0, 2, 0)), "hollow leaves an empty frame to light");
        assertEquals(OBSIDIAN, unlit.get(new Cell(-1, 2, 0)));
    }

    @Test
    void netherPortalSizesFollowMinecraftsLimits() {
        assertMessage("must be flat", "{\"op\":\"portal\",\"style\":\"nether\",\"from\":[0,0,0],\"to\":[2,3,2]}");
        assertMessage("1 by 3", "{\"op\":\"portal\",\"style\":\"nether\",\"from\":[0,0,0],\"to\":[0,2,0]}");
        assertMessage("2 by 2", "{\"op\":\"portal\",\"style\":\"nether\",\"from\":[0,0,0],\"to\":[1,1,0]}");
        assertMessage("22 by 3", "{\"op\":\"portal\",\"style\":\"nether\",\"from\":[0,0,0],\"to\":[21,2,0]}");
        assertMessage("needs \"to\"", "{\"op\":\"portal\",\"style\":\"nether\",\"from\":[0,0,0]}");
        assertMessage("nether or end", "{\"op\":\"portal\",\"style\":\"twilight\",\"from\":[0,0,0]}");
        assertDoesNotThrow(() -> plan("{\"op\":\"portal\",\"from\":[0,0,0],\"to\":[20,20,0]}"), "21 by 21 is the largest");
    }

    @Test
    void endPortalFramesFaceTheCenterWithEyes() throws Exception {
        var cells = plan("{\"op\":\"portal\",\"style\":\"end\",\"from\":[0,0,-6],\"to\":null}");
        for (int d = -1; d <= 1; d++) {
            assertEquals("minecraft:end_portal_frame[eye=true,facing=south]", cells.get(new Cell(d, 0, -8)), "north side");
            assertEquals("minecraft:end_portal_frame[eye=true,facing=north]", cells.get(new Cell(d, 0, -4)), "south side");
            assertEquals("minecraft:end_portal_frame[eye=true,facing=east]", cells.get(new Cell(-2, 0, -6 + d)), "west side");
            assertEquals("minecraft:end_portal_frame[eye=true,facing=west]", cells.get(new Cell(2, 0, -6 + d)), "east side");
            for (int e = -1; e <= 1; e++) {
                assertEquals("minecraft:end_portal", cells.get(new Cell(d, 0, -6 + e)));
            }
        }
        assertNull(cells.get(new Cell(2, 0, -8)), "the corners stay empty");
        assertEquals(21, cells.size());
        var unlit = plan("{\"op\":\"portal\",\"style\":\"end\",\"from\":[0,0,0],\"hollow\":true}");
        assertEquals("minecraft:end_portal_frame[eye=false,facing=south]", unlit.get(new Cell(0, 0, -2)));
        assertEquals(Rasterizer.AIR, unlit.get(new Cell(0, 0, 0)), "eyes left for the player");
    }

    @Test
    void portalsComeLastSoDecorationCannotBreakThem() throws Exception {
        var cells = plan("{\"op\":\"portal\",\"style\":\"nether\",\"from\":[0,1,0],\"to\":[1,3,0]},"
                + "{\"op\":\"fill\",\"block\":\"crying_obsidian\",\"from\":[-2,0,0],\"to\":[3,5,0],\"hollow\":false}");
        assertEquals(OBSIDIAN, cells.get(new Cell(-1, 1, 0)), "the frame survives a fill written after it");
        assertEquals("minecraft:nether_portal[axis=x]", cells.get(new Cell(0, 2, 0)));
        assertEquals("crying_obsidian", cells.get(new Cell(-2, 1, 0)), "decoration around it stays");
        var written = PlanParser.parse(PlanWriter.write(((PlanResponse.Build) PlanParser.parse(
                "{\"kind\":\"build\",\"title\":\"t\",\"operations\":[{\"op\":\"portal\",\"style\":\"end\",\"from\":[1,2,3]}]}"))
                .plan()));
        Operation end = ((PlanResponse.Build) written).plan().operations().get(0);
        assertEquals("end", end.style());
        assertNull(end.to());
        assertEquals(new Bounds(-1, 2, 1, 3, 2, 5), Bounds.of(end));
        assertEquals(new Bounds(-1, 0, 0, 2, 4, 0), Bounds.of(((PlanResponse.Build) PlanParser.parse(
                "{\"kind\":\"build\",\"title\":\"t\",\"operations\":[{\"op\":\"portal\",\"from\":[0,1,0],\"to\":[1,3,0]}]}"))
                .plan().operations().get(0)));
    }

    private static void assertMessage(String part, String op) {
        PlanException e = assertThrows(PlanException.class,
                () -> PlanParser.parse("{\"kind\":\"build\",\"operations\":[" + op + "]}"));
        assertTrue(e.getMessage().contains(part), e.getMessage());
    }
}
