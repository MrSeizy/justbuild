package dev.justbuild.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanLimitsShapesTest {
    final PlanLimits limits = new PlanLimits(100, 100_000, 10);

    @Test
    void boundsForNewShapes() {
        // Eaves one below, the gable rim one above the ridge.
        assertEquals(new Bounds(0, 3, 0, 4, 6, 2),
                Bounds.of(Operation.roof("oak_stairs", null, new Cell(0, 4, 0), new Cell(4, 4, 2), "gable", null)));
        assertEquals(new Bounds(0, -1, 0, 4, 2, 4),
                Bounds.of(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(4, 0, 4), "hip", null)));
        // A steep roof ends in a slab cap over its ridge, and its rim follows the roof instead of standing above it.
        assertEquals(new Bounds(0, -1, 0, 4, 3, 2),
                Bounds.of(Operation.roof("oak_stairs", null, new Cell(0, 0, 0), new Cell(4, 0, 2), "gable", null, null, 2)));
        assertEquals(new Bounds(0, -1, 0, 4, 5, 2), Bounds.of(Operation.roof("oak_stairs", null, new Cell(0, 0, 0),
                new Cell(4, 0, 2), "gable", null, null, 2, null, false, null, null, "oak_log",
                List.of("oak_fence", "lantern"))), "a beam hangs below even without eaves; finials stand on top");
        assertEquals(new Bounds(0, -1, 0, 4, 2, 8), Bounds.of(Operation.roof("oak_stairs", null, new Cell(0, 0, 0),
                new Cell(4, 0, 8), "gable", "x", null, 0)), "a low roof rises half a block per course");
        assertEquals(new Bounds(0, 0, 0, 4, 1, 2), Bounds.of(Operation.roof("oak_stairs", null, new Cell(0, 0, 0),
                new Cell(4, 0, 2), "gable", null, null, null, false, false)), "no rim above, no eaves below");
        assertEquals(new Bounds(0, 0, -2, 1, 2, 0),
                Bounds.of(Operation.stairs("oak_stairs", null, new Cell(0, 0, 0), new Cell(1, 0, 0), "north", 3)));
        assertEquals(new Bounds(-2, 0, 0, 2, 3, 0), Bounds.of(Operation.arch("stone", new Cell(0, 0, 0), 2, 1, "x", false)));
        assertEquals(new Bounds(10, 0, 20, 12, 1, 21), Bounds.of(Operation.voxels(Map.of('P', "s"), new Cell(10, 0, 20),
                List.of(List.of("PP.", "P P"), List.of("P")))));
        assertEquals(new Bounds(0, -1, 0, 3, -1, 3),
                Bounds.of(Operation.foundation("dirt", new Cell(0, -1, 0), new Cell(3, -1, 3))));
        assertEquals(new Bounds(-2, -1, -1, 2, 1, 1),
                Bounds.of(Operation.ellipsoid("s", new Cell(0, 0, 0), new Radii(2, 1, 1), "full", false)));
    }

    @Test
    void repeatAndMirrorWidenBounds() {
        assertEquals(new Bounds(0, 0, 0, 8, 0, 0),
                Bounds.of(Operation.set("s", new Cell(0, 0, 0)).withRepeat(new Repeat(5, new Cell(2, 0, 0)))));
        assertEquals(new Bounds(-4, 0, 0, 3, 0, 0),
                Bounds.of(Operation.set("s", new Cell(3, 0, 0)).withMirror(new MirrorPlane("x", -0.5))));
    }

    @Test
    void rejectsOversizedModifiersAndShapes() {
        assertMessage("repeats 300 times", Operation.set("s", new Cell(0, 0, 0)).withRepeat(new Repeat(300, new Cell(0, 0, 0))));
        assertMessage("repeat step larger than 10", Operation.set("s", new Cell(0, 0, 0)).withRepeat(new Repeat(2, new Cell(50, 0, 0))));
        assertMessage("radii exceed", Operation.ellipsoid("s", new Cell(0, 0, 0), new Radii(1, 20, 1), "full", false));
        assertMessage("plan is 9x1x1", Operation.set("s", new Cell(0, 0, 0)).withRepeat(new Repeat(5, new Cell(2, 0, 0))));
        assertMessage("repeats 0 times", Operation.set("s", new Cell(0, 0, 0)).withRepeat(new Repeat(0, new Cell(1, 0, 0))));
        assertMessage("repeats -1 times", Operation.set("s", new Cell(0, 0, 0)).withRepeat(new Repeat(-1, new Cell(1, 0, 0))));
    }

    private void assertMessage(String expected, Operation op) {
        PlanLimits strict = expected.startsWith("plan is") ? new PlanLimits(100, 100_000, 8) : limits;
        var e = assertThrows(PlanException.class, () -> strict.check(new BuildPlan("t", "", List.of(op))));
        assertTrue(e.getMessage().contains(expected), () -> "message was: " + e.getMessage());
    }
}
