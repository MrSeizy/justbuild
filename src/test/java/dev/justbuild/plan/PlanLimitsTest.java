package dev.justbuild.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanLimitsTest {
    final PlanLimits limits = new PlanLimits(3, 1000, 10);

    @Test
    void boundsPerShape() {
        assertEquals(new Bounds(1, 2, 3, 1, 2, 3), Bounds.of(Operation.set("s", new Cell(1, 2, 3))));
        assertEquals(new Bounds(-2, 0, -5, 4, 3, 0), Bounds.of(Operation.fill("s", new Cell(4, 3, -5), new Cell(-2, 0, 0), false)));
        assertEquals(new Bounds(-2, -2, -2, 2, 2, 2), Bounds.of(Operation.sphere("s", new Cell(0, 0, 0), 2.4, false)));
        assertEquals(new Bounds(-3, 5, -3, 3, 9, 3), Bounds.of(Operation.cylinder("s", new Cell(0, 5, 0), 2.6, 5, false)));
    }

    @Test
    void acceptsPlanWithinLimits() throws Exception {
        Bounds b = limits.check(plan(Operation.fill("s", new Cell(0, 0, 0), new Cell(9, 9, 9), false)));
        assertEquals(10, b.sizeX());
    }

    @Test
    void rejectsTooManyOperations() {
        List<Operation> ops = Collections.nCopies(4, Operation.set("s", new Cell(0, 0, 0)));
        var e = assertThrows(PlanException.class, () -> limits.check(new BuildPlan("t", "", ops)));
        assertTrue(e.getMessage().contains("4 operations; the limit is 3"));
    }

    @Test
    void rejectsOversizedPlan() {
        var e = assertThrows(PlanException.class, () -> limits.check(plan(
                Operation.set("s", new Cell(0, 0, 0)), Operation.set("s", new Cell(10, 0, 0)))));
        assertTrue(e.getMessage().contains("11x1x1"));
    }

    @Test
    void rejectsHugeScalarsBeforeComputingBounds() {
        var e = assertThrows(PlanException.class, () -> limits.check(plan(Operation.sphere("s", new Cell(0, 0, 0), 1e12, false))));
        assertTrue(e.getMessage().contains("radius"));
        var e2 = assertThrows(PlanException.class, () -> limits.check(plan(Operation.set("s", new Cell(Integer.MAX_VALUE, 0, 0)))));
        assertTrue(e2.getMessage().contains("coordinate"));
    }

    private static BuildPlan plan(Operation... ops) {
        return new BuildPlan("t", "", List.of(ops));
    }
}
