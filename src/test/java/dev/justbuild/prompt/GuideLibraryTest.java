package dev.justbuild.prompt;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuideLibraryTest {
    @Test
    void loadsTwentySixShortGuides() {
        GuideLibrary library = GuideLibrary.defaults();
        assertEquals(26, library.all().size());
        assertEquals(26, new HashSet<>(library.all().stream().map(GuideLibrary.Guide::id).toList()).size());
        for (GuideLibrary.Guide g : library.all()) {
            assertTrue(g.id().matches("[a-z]+(-[a-z]+)*"), g.id());
            assertFalse(g.title().isBlank(), g.id());
            assertTrue(g.menu().length() <= 110, g.id() + " menu line is too long for the system prompt");
            // Each guide costs tokens on every request that reads it, so they stay short.
            assertTrue(g.text().length() > 200 && g.text().length() <= 1600, g.id() + ": " + g.text().length());
        }
    }

    @Test
    void resolvesOnlySelectedGuidesInTheirOrderWithinTheSharedLimit() {
        GuideLibrary library = GuideLibrary.defaults();
        var chosen = List.of("castles", "towers", "interiors", "landscaping", "glowing-ceilings", "chimneys");
        assertEquals(chosen, ids(library.pick(chosen)));
        assertEquals(chosen, ids(library.resolve(chosen)));
        assertEquals(List.of("castles", "interiors"), ids(library.pick(
                List.of("Castles ", "unknown", "castles", "interiors"))));
        assertTrue(library.pick(List.of("unknown")).isEmpty());
        var excess = new java.util.ArrayList<>(chosen); excess.add("windows");
        assertEquals(chosen, ids(library.pick(excess)), "the seventh guide must not extend the budget");
        assertEquals(chosen, ids(library.resolve(excess)));
    }

    @Test
    void menuListsEveryGuide() {
        GuideLibrary library = GuideLibrary.defaults();
        String menu = library.menu();
        library.all().forEach(g -> assertTrue(menu.contains("- " + g.id() + ": "), g.id()));
        assertEquals(library.all().size(), menu.lines().count());
    }

    private static List<String> ids(List<GuideLibrary.Guide> guides) {
        return guides.stream().map(GuideLibrary.Guide::id).toList();
    }
}
