package dev.justbuild.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanSizeTest {
    static BuildPlan plan(Operation... ops) {
        return new BuildPlan("t", "s", List.of(ops));
    }

    @Test
    void countsBlocksOnFlatGround() throws Exception {
        BuildPlan cube = plan(Operation.fill("minecraft:stone", new Cell(0, 0, 0), new Cell(9, 9, 9), false));
        assertEquals(1000, PlanSize.blocks(cube, 5000));
        BuildPlan shell = plan(Operation.fill("minecraft:stone", new Cell(0, 0, 0), new Cell(9, 9, 9), true));
        assertEquals(1000, PlanSize.blocks(shell, 5000), "the air that clears a hollow box's inside counts too");
    }

    @Test
    void stopsCountingAtTheCap() throws Exception {
        BuildPlan big = plan(Operation.fill("minecraft:stone", new Cell(0, 0, 0), new Cell(49, 49, 49), false));
        assertEquals(PlanSize.OVER, PlanSize.blocks(big, 20000));
        assertEquals("about 1,000", PlanSize.describe(1000, 5000));
        assertEquals("more than 20,000", PlanSize.describe(PlanSize.OVER, 20000));
    }
}
