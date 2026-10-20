package dev.justbuild.session;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanStoreTest {
    @TempDir
    Path dir;

    final Clock clock = Clock.fixed(Instant.parse("2026-10-11T14:03:09Z"), ZoneOffset.UTC);

    @Test
    void slugs() {
        assertEquals("oak-cabin-with-porch", PlanStore.slug("  Oak Cabin (with porch)! "));
        assertEquals("build", PlanStore.slug("!!!"));
        assertEquals("build", PlanStore.slug(null));
        assertEquals(40, PlanStore.slug("a".repeat(60)).length());
    }

    @Test
    void savesWithTimestampAndAvoidsCollisions() throws Exception {
        PlanStore store = new PlanStore(dir.resolve("plans"), clock);
        Path first = store.save("{\"a\":1}", "Oak Cabin");
        Path second = store.save("{\"a\":2}", "Oak Cabin");
        Path invalid = store.saveInvalid("not json");
        assertEquals("20261011-140309-oak-cabin.json", first.getFileName().toString());
        assertEquals("20261011-140309-oak-cabin-2.json", second.getFileName().toString());
        assertEquals("20261011-140309-invalid.json", invalid.getFileName().toString());
        assertEquals("{\"a\":2}", Files.readString(second));
    }

    @Test
    void keepsOnlyTheNewestPlans() throws Exception {
        PlanStore store = new PlanStore(dir.resolve("plans"), clock, 5);
        for (int i = 0; i < 8; i++) store.save("{\"n\":" + i + "}", "Cabin");
        try (var files = Files.list(dir.resolve("plans"))) {
            var kept = files.map(f -> {
                try { return Files.readString(f); } catch (Exception e) { throw new RuntimeException(e); }
            }).sorted().toList();
            assertEquals(java.util.List.of("{\"n\":3}", "{\"n\":4}", "{\"n\":5}", "{\"n\":6}", "{\"n\":7}"), kept);
        }
    }
}
