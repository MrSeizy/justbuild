package dev.justbuild.session;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.PlanParser;
import dev.justbuild.plan.PlanResponse;
import dev.justbuild.relay.PromptCheck;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PreviewRevisionsTest {
    @BeforeAll
    static void registries() {
        dev.justbuild.world.McBootstrap.init();
    }

    private static BuildPlan plan(String title) throws Exception {
        String json = "{\"kind\":\"build\",\"title\":\"" + title + "\",\"summary\":\"s\",\"operations\":[{\"op\":\"fill\","
                + "\"block\":\"minecraft:stone\",\"from\":[0,0,0],\"to\":[1,0,0]}]}";
        return ((PlanResponse.Build) PlanParser.parse(json)).plan();
    }

    private static PreviewRevisions.Earlier version(String title, String description) throws Exception {
        return new PreviewRevisions.Earlier(plan(title), title, "summary of " + title, description);
    }

    @Test
    void revisionsStackAndStepBack() throws Exception {
        var first = PreviewRevisions.NONE.after("steeper roof", version("Hut", "a hut"));
        assertEquals("a hut", first.original());
        assertEquals(List.of("steeper roof"), first.changes());
        assertEquals(1, first.count());
        assertTrue(first.hasPrevious());
        var second = first.after("add a chimney", version("Steep hut", "a hut (revised: steeper roof)"));
        assertEquals("a hut", second.original(), "the original request is fixed at the first revision");
        assertEquals(2, second.count());
        assertEquals("Steep hut", second.latest().title());
        var back = second.previous();
        assertEquals(first, back);
        assertEquals("Hut", back.latest().title());
        var none = back.previous();
        assertFalse(none.hasPrevious());
        assertEquals(0, none.count());
        assertEquals(List.of(), none.changes());
        assertThrows(IllegalStateException.class, none::previous);
        assertThrows(IllegalStateException.class, none::latest);
    }

    @Test
    void keepsTenEarlierVersionsAndCountsEveryRevision() throws Exception {
        var revisions = PreviewRevisions.NONE;
        for (int i = 1; i <= 12; i++) revisions = revisions.after("change " + i, version("V" + i, "d" + i));
        assertEquals(12, revisions.count());
        assertEquals(PreviewRevisions.MAX_EARLIER, revisions.earlier().size());
        assertEquals("V3", revisions.earlier().getFirst().title(), "the two oldest versions were dropped");
        assertEquals("change 3", revisions.changes().getFirst());
        assertEquals("d1", revisions.original());
        for (int i = 0; i < PreviewRevisions.MAX_EARLIER; i++) revisions = revisions.previous();
        assertFalse(revisions.hasPrevious());
        assertEquals(2, revisions.count(), "dropped versions still count as revisions made");
    }

    @Test
    void descriptionAndPromptHistoryStayWithinLimits() throws Exception {
        assertEquals("a hut (revised: steeper roof)", PreviewRevisions.description("a hut", "steeper roof"));
        String capped = PreviewRevisions.description("x".repeat(20_000), "steeper roof");
        assertEquals(PreviewRevisions.MAX_DESCRIPTION, capped.length());
        assertTrue(capped.endsWith("…"));
        var revisions = PreviewRevisions.NONE.after("x".repeat(700), version("Hut", "y".repeat(900)));
        var history = revisions.history("ignored once an original exists");
        assertEquals("y".repeat(PromptCheck.MAX_HISTORY_TEXT), history.original());
        assertEquals(List.of("x".repeat(PromptCheck.MAX_HISTORY_TEXT)), history.earlier());
        assertEquals("a retried plan", PreviewRevisions.NONE.history("a retried plan").original());
        assertEquals(List.of(), PreviewRevisions.NONE.history("a retried plan").earlier());
        assertTrue(PreviewRevisions.MAX_EARLIER <= PromptCheck.MAX_HISTORY);
    }

    @Test
    void rejectsInconsistentHistories() throws Exception {
        var earlier = version("Hut", "a hut");
        assertThrows(IllegalArgumentException.class, () -> new PreviewRevisions("a hut", List.of(), List.of(earlier), 1));
        assertThrows(IllegalArgumentException.class, () -> new PreviewRevisions("a hut", List.of("x"), List.of(earlier), 0));
        assertThrows(NullPointerException.class, () -> new PreviewRevisions.Earlier(null, "t", "s", "d"));
    }
}
