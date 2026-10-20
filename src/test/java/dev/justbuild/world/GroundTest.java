package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

class GroundTest {
    /** Solid between each pair of inclusive heights. */
    private static IntPredicate solid(int... ranges) {
        return y -> {
            for (int i = 0; i < ranges.length; i += 2) if (y >= ranges[i] && y <= ranges[i + 1]) return true;
            return false;
        };
    }

    @Test
    void standsOnTheCavernFloorUnderTheRoof() {
        assertEquals(64, Ground.scan(solid(0, 63, 123, 127), 0, 255, 100));
    }

    @Test
    void climbsOutOfTheBlockItStartsIn() {
        assertEquals(81, Ground.scan(solid(0, 80, 123, 127), 0, 255, 70));
    }

    @Test
    void standsOnTheRoofWhenStartingAboveIt() {
        assertEquals(128, Ground.scan(solid(0, 63, 123, 127), 0, 255, 140));
    }

    @Test
    void keepsTheStartingHeightWhenThereIsNoFloor() {
        assertEquals(70, Ground.scan(y -> false, 0, 255, 70));
        assertEquals(70, Ground.scan(y -> true, 0, 255, 70));
    }
}
