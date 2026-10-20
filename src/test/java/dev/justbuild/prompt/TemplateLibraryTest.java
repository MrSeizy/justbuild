package dev.justbuild.prompt;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class TemplateLibraryTest {
    @Test
    void loadsTwentyFourTemplatesInSixCategories() {
        TemplateLibrary library = TemplateLibrary.defaults();
        assertEquals(24, library.all().size());
        assertEquals(6, new HashSet<>(library.all().stream().map(TemplateLibrary.Template::category).toList()).size());
        assertEquals(24, new HashSet<>(library.all().stream().map(TemplateLibrary.Template::id).toList()).size());
        for (TemplateLibrary.Template t : library.all()) {
            String text = t.request().toLowerCase(Locale.ROOT);
            assertFalse(text.contains("lava") || text.contains("tnt") || text.contains(" fire"), t.id());
            assertTrue(t.request().length() <= 230, t.id() + " must fit in a chat command");
        }
    }

    @Test
    void filtersByNameCategoryOrRequest() {
        TemplateLibrary library = TemplateLibrary.defaults();
        assertEquals("castle", library.byId("castle").orElseThrow().id());
        assertTrue(library.byId("nope").isEmpty());
        assertTrue(library.filter("Castle").stream().anyMatch(t -> t.id().equals("castle")));
        assertTrue(library.filter("monuments").stream().allMatch(t -> t.category().equals("Monuments")));
        assertTrue(library.filter("koi").stream().anyMatch(t -> t.id().equals("temple")));
        assertEquals(24, library.filter("").size());
    }
}
