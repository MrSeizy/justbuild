package dev.justbuild.plan;

import static dev.justbuild.plan.Rasterizer.AIR;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RasterizerTest {
    static final String S = "minecraft:stone";

    static Map<Cell, String> run(Operation... ops) throws PlanException {
        return specs(Rasterizer.rasterize(List.of(ops), 100_000));
    }

    static Map<Cell, String> specs(Map<Cell, BlockRef> cells) {
        Map<Cell, String> out = new java.util.LinkedHashMap<>();
        cells.forEach((cell, ref) -> out.put(cell, ref.spec()));
        return out;
    }

    static long count(Map<Cell, String> cells, String block) {
        return cells.values().stream().filter(block::equals).count();
    }

    @Test
    void set() throws Exception {
        assertEquals(Map.of(new Cell(1, 2, 3), S), run(Operation.set(S, new Cell(1, 2, 3))));
    }

    @Test
    void solidAndHollowFill() throws Exception {
        assertEquals(27, count(run(Operation.fill(S, new Cell(0, 0, 0), new Cell(2, 2, 2), false)), S));
        var hollow = run(Operation.fill(S, new Cell(0, 0, 0), new Cell(2, 2, 2), true));
        assertEquals(26, count(hollow, S));
        assertEquals(AIR, hollow.get(new Cell(1, 1, 1)));
    }

    @Test
    void laterOperationsOverwrite() throws Exception {
        var cells = run(Operation.fill(S, new Cell(0, 0, 0), new Cell(2, 2, 2), false), Operation.set(AIR, new Cell(1, 1, 1)));
        assertEquals(27, cells.size());
        assertEquals(AIR, cells.get(new Cell(1, 1, 1)));
    }

    @Test
    void lines() throws Exception {
        assertEquals(4, run(Operation.line(S, new Cell(0, 0, 0), new Cell(3, 0, 0))).size());
        assertEquals(
                List.of(new Cell(0, 0, 0), new Cell(1, 1, 1), new Cell(2, 2, 2)),
                List.copyOf(run(Operation.line(S, new Cell(0, 0, 0), new Cell(2, 2, 2))).keySet()));
        assertEquals(1, run(Operation.line(S, new Cell(5, 5, 5), new Cell(5, 5, 5))).size());
    }

    @Test
    void spheres() throws Exception {
        // radius 1 keeps center, 6 faces, 12 edges (distance sqrt 2 <= 1.5) and drops 8 corners (sqrt 3)
        assertEquals(19, run(Operation.sphere(S, new Cell(0, 0, 0), 1, false)).size());
        var hollow = run(Operation.sphere(S, new Cell(0, 0, 0), 2, true));
        assertEquals(AIR, hollow.get(new Cell(0, 0, 0)));
        assertEquals(S, hollow.get(new Cell(2, 0, 0)));
    }

    @Test
    void cylinders() throws Exception {
        assertEquals(18, run(Operation.cylinder(S, new Cell(0, 0, 0), 1, 2, false)).size());
        var hollow = run(Operation.cylinder(S, new Cell(0, 0, 0), 2, 1, true));
        assertEquals(21, hollow.size());
        assertEquals(12, count(hollow, S));
        assertEquals(9, count(hollow, AIR));
    }

    @Test
    void stopsAtCellLimit() {
        var e = assertThrows(PlanTooLargeException.class, () -> Rasterizer.rasterize(
                List.of(Operation.fill(S, new Cell(0, 0, 0), new Cell(9, 9, 9), false)), 999));
        assertTrue(e.getMessage().contains("more than 999 blocks"));
        assertEquals(999, e.limit());
    }

    @Test
    void workIsBoundedEvenWhenNothingIsWritten() {
        java.util.concurrent.atomic.AtomicLong reads = new java.util.concurrent.atomic.AtomicLong();
        WorldQuery counting = new WorldQuery() {
            @Override
            public String blockId(Cell cell) {
                reads.incrementAndGet();
                return "minecraft:air";
            }

            @Override
            public boolean isSolid(Cell cell) {
                reads.incrementAndGet();
                return false;
            }
        };
        Operation replace = new Operation(OpType.REPLACE, "stone", new Cell(0, 0, 0), new Cell(95, 95, 95), null, null,
                false, null, null, null, null, null, null, null, "diamond_block", new Repeat(256, new Cell(0, 0, 0)),
                new MirrorPlane("x", 47.5));
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () ->
                assertThrows(PlanTooMuchWorkException.class, () -> Rasterizer.rasterize(List.of(replace), 50000, counting)));
        assertTrue(reads.get() <= 8_000_000, "read " + reads.get() + " cells before stopping");
    }

    @Test
    void shapesFarLargerThanAnyLimitStopAtOnce() {
        Operation dome = Operation.sphere("stone", new Cell(0, 0, 0), 1e300, "dome", false);
        Operation spin = new Operation(OpType.SET, "stone", new Cell(0, 0, 0), null, null, null, false, null, null, null,
                null, null, null, null, null, new Repeat(2_000_000_000, new Cell(0, 0, 0)), null);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            assertThrows(PlanTooMuchWorkException.class, () -> Rasterizer.rasterize(List.of(dome), 50000));
            assertThrows(PlanTooMuchWorkException.class, () -> Rasterizer.rasterize(List.of(spin), 50000));
            assertThrows(PlanException.class, () -> PlanSize.blocks(new BuildPlan("Dome", "", List.of(dome)), 200000));
        });
    }

    @Test
    void largeLegitimateShapesStillDraw() throws PlanException {
        Operation box = Operation.fill("stone", new Cell(0, 0, 0), new Cell(29, 19, 29), true);
        Operation dome = Operation.sphere("glass", new Cell(15, 20, 15), 15, "dome", true);
        Operation swap = Operation.replace("oak_planks", "stone", new Cell(-15, -5, -15), new Cell(44, 24, 44));
        assertFalse(Rasterizer.rasterize(List.of(box, dome, swap), 50000).isEmpty());
        // Stored builds are recomputed without a work limit.
        Operation spin = new Operation(OpType.SET, "stone", new Cell(0, 0, 0), null, null, null, false, null, null, null,
                null, null, null, null, null, new Repeat(256, new Cell(0, 0, 0)), null);
        assertEquals(1, Rasterizer.rasterize(List.of(spin), Integer.MAX_VALUE).size());
    }
}
