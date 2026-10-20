package dev.justbuild.relay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.Operation;
import dev.justbuild.plan.PlanWriter;
import dev.justbuild.prompt.AlternativeRequests;
import dev.justbuild.prompt.PlanningAreas;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdvancedPlanningTest {
    @org.junit.jupiter.api.BeforeAll
    static void registries() { dev.justbuild.world.McBootstrap.init(); }

    @Test void planningAreasSurviveRelayAndAllFollowUpCopies() throws Exception {
        var box = new PlanningAreas.Box(-2, 0, -3, 2, 5, 3);
        var areas = new PlanningAreas(box, List.of(box), List.of());
        String plan = PlanWriter.write(new BuildPlan("Test", "", List.of(Operation.set("stone", new Cell(0, 0, 0)))));
        PromptSpec spec = PromptSpec.edit("minecraft:overworld", "minecraft:plains", "a house", List.of(), plan,
                "replace the roof", List.of(), 0, 100).withAreas(areas);
        assertEquals(areas, PromptSpec.parse(spec.toJson()).areas());
        assertEquals(areas, spec.withGuides(false, List.of()).withWorld(null, List.of(), 0).shrink(plan).areas());
        assertTrue(spec.userContent().contains("Focused edit"));
        assertNull(PromptCheck.check(spec, new PromptCheck.Seen(Set.of("replace the roof"), Set.of(), Set.of())).problem());
        assertEquals(areas, PromptCheck.check(spec, new PromptCheck.Seen(Set.of("replace the roof"), Set.of(), Set.of())).spec().areas());
        var malformed = JsonParser.parseString("{\"focus\":{\"minX\":0.5,\"minY\":0,\"minZ\":0,\"maxX\":1,\"maxY\":1,\"maxZ\":1}}").getAsJsonObject();
        assertThrows(RuntimeException.class, () -> PlanningAreas.parse(malformed));
        assertThrows(IllegalArgumentException.class, () -> new PlanningAreas.Box(-4097, 0, 0, -4096, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlanningAreas(null, List.of(), List.of(box)));
    }

    @Test void alternativeRequestsAreDistinctAndBounded() {
        String base = "a small house";
        var command = AlternativeRequests.parse("edit 3 #abcdef12 " + base);
        assertTrue(command.edit()); assertEquals(3, command.count());
        assertNotEquals(AlternativeRequests.variant(base, 1, 3), AlternativeRequests.variant(base, 2, 3));
        assertTrue(AlternativeRequests.variant(base, 3, 3).endsWith(base));
        assertThrows(IllegalArgumentException.class, () -> AlternativeRequests.variant(base, 4, 3));
        assertThrows(IllegalArgumentException.class, () -> AlternativeRequests.parse("build 4 " + base));
    }

    @Test void onlyExplicitAlternativesIncreaseTheAllowanceAndHourlyCapStillApplies() {
        RelayGate gate = new RelayGate(); Instant now = Instant.parse("2026-10-13T20:00:00Z");
        gate.command("justbuild preview option 2", now); assertFalse(gate.allow(now));
        gate.command("justbuild lock Bedroom", now); assertFalse(gate.allow(now));
        gate.command("justbuild alternatives build 3 a house", now);
        for (int i = 0; i < 30; i++) assertTrue(gate.allow(now));
        assertFalse(gate.allow(now));
        gate.command("justbuild alternatives edit 2 #abcdef12 change the roof", now);

        assertFalse(gate.allow(now), "the rolling hourly ceiling must remain thirty");
    }
}
