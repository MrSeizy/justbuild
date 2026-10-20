package dev.justbuild.session;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class EditTargetTest {
    @Test
    void parsesAnOptionalBuildId() {
        assertEquals(new EditTarget(Optional.of("0a1b2c3d"), "add lighting"), EditTarget.parse("#0a1b2c3d add lighting"));
        assertEquals(new EditTarget(Optional.empty(), "make it taller"), EditTarget.parse("  make it taller "));
        assertEquals(new EditTarget(Optional.empty(), "#tall towers"), EditTarget.parse("#tall towers"));
        assertEquals(new EditTarget(Optional.of("0a1b2c3d"), ""), EditTarget.parse("#0a1b2c3d"));
    }

    @Test
    void stripsTheCreativeDirectionForAgain() {
        assertEquals("a castle (How big? Large)", EditTarget.withoutDirection("a castle (direction: tall and narrow) (How big? Large)"));
        assertEquals("a hut", EditTarget.withoutDirection("a hut"));
    }
}
