package dev.justbuild.relay;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.prompt.InteriorRequests;
import dev.justbuild.prompt.ProjectTheme;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkshopPlanningTest {
    @Test void projectThemeSurvivesWireReviewShrinkAndValidation() throws Exception {
        var theme = new ProjectTheme("medieval", List.of("minecraft:stone_bricks", "minecraft:oak_planks"));
        var spec = PromptSpec.newBuild("minecraft:overworld", "minecraft:plains", null, null, null, "a house", List.of(), 0, null, 100).withTheme(theme);
        var parsed = PromptSpec.parse(spec.toJson()); assertEquals(theme, parsed.theme());
        var checked = PromptCheck.check(parsed, new PromptCheck.Seen(Set.of("a house"), Set.of(), Set.of()));
        assertNull(checked.problem()); assertEquals(theme, checked.spec().theme());
        assertTrue(spec.userContent().contains("Shared village project"));
        String plan = "{\"kind\":\"build\",\"title\":\"Test\",\"summary\":\"\",\"operations\":[{\"op\":\"set\",\"block\":\"stone\",\"from\":[0,0,0]}]}";
        assertEquals(theme, spec.review(plan, 1, 1).theme()); assertEquals(theme, spec.shrink(plan).theme());
        var legacy = spec.toJson(); legacy.remove("theme"); assertEquals(ProjectTheme.EMPTY, PromptSpec.parse(legacy).theme());
    }
    @Test void interiorTemplatesFitRelayBoundsAndLocalToolsDoNotAuthorizeAiCalls() {
        for (String purpose : InteriorRequests.PURPOSES) for (String stage : InteriorRequests.STAGES) {
            String request = InteriorRequests.request(purpose, stage, "x".repeat(180)); assertTrue(request.length() <= PromptCheck.MAX_TEXT);
            var choice = InteriorRequests.parse(purpose + " " + stage + " specific rooms"); assertEquals("specific rooms", choice.detail());
        }
        var gate = new RelayGate(); Instant now = Instant.now();
        for (String command : List.of("add House", "materials replace oak_planks stone", "repair all", "project create Town", "walkability")) {
            gate.command("justbuild " + command, now); assertFalse(gate.allow(now), command);
        }
        gate.command("justbuild interior home complete", now); assertTrue(gate.allow(now));
    }
}
