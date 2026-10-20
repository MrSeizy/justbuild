package dev.justbuild.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanViewsTest {
    static final String VIEWS = render(
            Operation.fill("minecraft:stone", new Cell(-1, 0, -2), new Cell(1, 0, 0), false),
            Operation.set("minecraft:gold_block", new Cell(0, 1, -1)),
            Operation.set("minecraft:air", new Cell(1, 0, 0)));

    static String render(Operation... ops) {
        try {
            return PlanViews.render(Rasterizer.rasterize(List.of(ops), 1000));
        } catch (PlanException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void legendIsOrderedByFrequency() {
        assertTrue(VIEWS.startsWith("Legend: a = minecraft:stone, b = minecraft:gold_block, . = empty"), VIEWS);
    }

    @Test
    void topFrontAndSideViews() {
        // top: rows z = -2, -1, 0; columns x = -1, 0, 1; gold sits on the middle; the carved corner is empty
        assertTrue(VIEWS.contains("Top view") && VIEWS.contains("\naaa\naba\naa.\n"), VIEWS);
        // front: rows y = 1, 0; columns x = -1, 0, 1
        assertTrue(VIEWS.contains("Front view") && VIEWS.contains("\n.b.\naaa\n"), VIEWS);
        // side: rows y = 1, 0; columns z = -2, -1, 0
        assertTrue(VIEWS.contains("Side view") && VIEWS.contains("\n.b.\naaa"), VIEWS);
    }

    @Test
    void stairsVariantsShareOneLegendEntry() {
        String views = render(Operation.set("minecraft:oak_stairs[facing=north]", new Cell(0, 0, 0)),
                Operation.set("minecraft:oak_stairs[facing=south]", new Cell(1, 0, 0)));
        assertTrue(views.startsWith("Legend: a = minecraft:oak_stairs, . = empty"), views);
    }

    @Test
    void emptyPlan() {
        assertEquals("The plan places no blocks.", PlanViews.render(java.util.Map.of()));
    }
}
