package dev.justbuild.plan;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PlanRotationTest {
    static final Cell FORWARD = new Cell(0, 0, -1); // north in the plan frame
    static final Cell RIGHT = new Cell(1, 0, 0);    // east in the plan frame

    @Test
    void facingToRotation() {
        assertEquals(PlanRotation.NONE, PlanRotation.forFacing("north"));
        assertEquals(PlanRotation.CLOCKWISE_90, PlanRotation.forFacing("EAST"));
        assertEquals(PlanRotation.CLOCKWISE_180, PlanRotation.forFacing("south"));
        assertEquals(PlanRotation.COUNTERCLOCKWISE_90, PlanRotation.forFacing("west"));
        assertThrows(IllegalArgumentException.class, () -> PlanRotation.forFacing("up"));
    }

    @Test
    void forwardBecomesTheFacingDirection() {
        assertEquals(new Cell(0, 0, -1), PlanRotation.NONE.apply(FORWARD));
        assertEquals(new Cell(1, 0, 0), PlanRotation.CLOCKWISE_90.apply(FORWARD));
        assertEquals(new Cell(0, 0, 1), PlanRotation.CLOCKWISE_180.apply(FORWARD));
        assertEquals(new Cell(-1, 0, 0), PlanRotation.COUNTERCLOCKWISE_90.apply(FORWARD));
    }

    @Test
    void inverseUndoesEachTurn() {
        Cell c = new Cell(3, 4, -7);
        for (PlanRotation turn : PlanRotation.values()) {
            assertEquals(c, turn.inverse().apply(turn.apply(c)), turn.name());
        }
        assertEquals(PlanRotation.COUNTERCLOCKWISE_90, PlanRotation.CLOCKWISE_90.inverse());
    }

    @Test
    void rightStaysOnThePlayersRight() {
        // facing east, the player's right is south (+z); facing west, it is north (-z)
        assertEquals(new Cell(0, 0, 1), PlanRotation.CLOCKWISE_90.apply(RIGHT));
        assertEquals(new Cell(0, 0, -1), PlanRotation.COUNTERCLOCKWISE_90.apply(RIGHT));
        assertEquals(new Cell(5, 7, 0), PlanRotation.CLOCKWISE_90.apply(new Cell(0, 7, -5)));
    }
}
