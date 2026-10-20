package dev.justbuild.prompt;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class GuidanceTest {
    @Test
    void detailLevels() {
        assertEquals(Optional.of(Guidance.DetailLevel.MASTERPIECE), Guidance.DetailLevel.parse(" Masterpiece "));
        assertEquals(Optional.empty(), Guidance.DetailLevel.parse("epic"));
        assertEquals(0, Guidance.DetailLevel.QUICK.reviewRounds());
        assertEquals(1, Guidance.DetailLevel.DETAILED.reviewRounds());
        assertEquals(2, Guidance.DetailLevel.MASTERPIECE.reviewRounds());
        assertTrue(Guidance.DetailLevel.MASTERPIECE.prompt().contains("10 review"));
        assertTrue(Guidance.DetailLevel.QUICK.prompt().startsWith("Detail level: quick."));
    }

    @Test
    void styles() {
        assertEquals(Optional.of(Guidance.Style.JAPANESE), Guidance.Style.parse("japanese"));
        assertEquals(Optional.empty(), Guidance.Style.parse("baroque"));
        assertNull(Guidance.Style.AUTO.promptLine());
        assertTrue(Guidance.Style.NORDIC.promptLine().startsWith("nordic, suggested palette: spruce logs"));
        assertTrue(Guidance.Style.MEDIEVAL.promptLine().contains("masonry fortifications"));
        assertTrue(Guidance.Style.MODERN.promptLine().contains("clear planes"));
        for (Guidance.Style style : Guidance.Style.values()) {
            assertEquals(style == Guidance.Style.AUTO, style.promptLine() == null, style.id());
        }
    }

    @Test
    void creativeDirectionsAreDrawnFromTheList() {
        String a = Guidance.direction(new Random(1));
        assertEquals(a, Guidance.direction(new Random(1)));
        assertTrue(Guidance.DIRECTIONS.contains(a));
        assertTrue(Guidance.DIRECTIONS.size() >= 12);
    }
}
