package dev.justbuild.relay;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.plan.Cell;
import dev.justbuild.prompt.BuildContext;
import dev.justbuild.prompt.EditContext;
import dev.justbuild.prompt.Exchange;
import dev.justbuild.prompt.Guidance;
import dev.justbuild.prompt.PromptBuilder;
import dev.justbuild.prompt.TerrainSummary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptSpecTest {
    static final String PLAN = "{\"kind\":\"build\",\"question\":null,\"options\":null,\"title\":\"Hut\",\"summary\":\"s\","
            + "\"operations\":[{\"op\":\"fill\",\"block\":\"minecraft:stone\",\"from\":[0,0,-4],\"to\":[2,2,-2],"
            + "\"radius\":null,\"height\":null,\"hollow\":null}]}";

    static TerrainSummary terrain() {
        Map<String, Integer> surface = new LinkedHashMap<>();
        surface.put("minecraft:grass_block", 12);
        surface.put("minecraft:sand", 3);
        return new TerrainSummary(4, -8, -16, new int[][] {{-1, 0, TerrainSummary.UNKNOWN}, {-1, -1, -2}}, surface);
    }

    static PromptSpec newBuild() {
        return PromptSpec.newBuild("minecraft:overworld", "minecraft:plains", new PromptSpec.Placement(14, 1, 2, 9),
                "nordic", "tall and narrow", "a fishing hut", List.of(new Exchange("How big?", "small")), 2, terrain(),
                50000);
    }

    static PromptSpec revision() {
        return PromptSpec.revision("minecraft:overworld", "minecraft:plains", "nordic", "a fishing hut",
                List.of("steeper roof"), PLAN, "add a chimney", List.of(new Exchange("Brick?", "yes")), 1, terrain(), 50000);
    }

    @Test
    void newBuildTextMatchesPromptBuilder() throws Exception {
        PromptSpec spec = newBuild();
        BuildContext context = new BuildContext("minecraft:overworld", "minecraft:plains",
                PromptBuilder.targetPlacement(14, new Cell(1, 2, 9)), Guidance.Style.NORDIC.promptLine(),
                "tall and narrow");
        assertEquals(PromptBuilder.userContent(context, "a fishing hut", List.of(new Exchange("How big?", "small")), 2,
                terrain()), spec.userContent());
        PromptSpec front = PromptSpec.newBuild("d:d", "b:b", PromptSpec.Placement.FRONT, null, null, "x", List.of(), 0,
                null, 100);
        assertTrue(front.userContent().contains(PromptBuilder.frontPlacement()));
    }

    @Test
    void autoStyleLeavesInterpretationToTheAiWhileExplicitStyleAndThemeRemain() throws Exception {
        PromptSpec castle = PromptSpec.newBuild("d:d", "b:b", PromptSpec.Placement.FRONT, "auto", null,
                "a medieval castle with towers, windows and furnished rooms", List.of(), 0, null, 100);
        assertFalse(castle.userContent().contains("- Style:"), "Auto must not inject a house preset");
        assertFalse(castle.review(PLAN, 1, 1).userContent().contains("- Style:"));
        assertTrue(castle.userContent().contains("a medieval castle"), "the actual request still supplies its style");
        PromptSpec answered = PromptSpec.newBuild("d:d", "b:b", PromptSpec.Placement.FRONT, "auto", null, "a house",
                List.of(new Exchange("Which style?", "Japanese")), 0, null, 100);
        assertFalse(answered.userContent().contains("- Style:"));
        assertTrue(answered.userContent().contains("Player answered: Japanese"));
        PromptSpec set = PromptSpec.newBuild("d:d", "b:b", PromptSpec.Placement.FRONT, "modern", null,
                "a house", List.of(), 0, null, 100);
        assertTrue(set.userContent().contains("\n- Style: " + Guidance.Style.MODERN.promptLine() + "\n"));
        PromptSpec themed = castle.withTheme(new dev.justbuild.prompt.ProjectTheme("desert", List.of()));
        assertFalse(themed.userContent().contains("- Style:"));
        assertTrue(themed.userContent().contains(Guidance.Style.DESERT.promptLine()));
    }

    @Test
    void editReviewAndShrinkTextMatchPromptBuilder() throws Exception {
        PromptSpec edit = PromptSpec.edit("minecraft:overworld", "minecraft:plains", "a hut", List.of("taller"), PLAN,
                "add a door", List.of(), 1, 50000);
        assertEquals(PromptBuilder.editContent(new BuildContext("minecraft:overworld", "minecraft:plains"),
                new EditContext("a hut", List.of("taller"), PLAN, "add a door"), List.of(), 1), edit.userContent());

        PromptSpec review = newBuild().review(PLAN, 1, 2);
        assertTrue(review.userContent().contains("round 1 of 2"));
        assertTrue(review.userContent().contains("Views of the plan:"));
        PromptSpec shrink = newBuild().shrink(PLAN);
        assertTrue(shrink.userContent().contains("places about 27 blocks, over the limit of 50000"));
    }

    @Test
    void guidesComeFromTheGamesOwnCopy() throws Exception {
        dev.justbuild.prompt.GuideLibrary.Guide windows =
                dev.justbuild.prompt.GuideLibrary.defaults().byId("windows").orElseThrow();
        PromptSpec guided = newBuild().withGuides(false, List.of("windows"));
        assertEquals(PromptBuilder.withGuides(newBuild().userContent(), List.of(windows)), guided.userContent());
        assertTrue(guided.review(PLAN, 1, 1).userContent().contains(windows.text()), "review rounds keep the guides");
        assertFalse(guided.shrink(PLAN).userContent().contains(windows.text()), "smaller-plan requests drop them");
        dev.justbuild.plan.PlanLimits limits = new dev.justbuild.plan.PlanLimits(2000, 50000, 96);
        assertTrue(newBuild().withGuides(true, List.of()).systemPrompt(limits, Guidance.DetailLevel.DETAILED)
                .contains("- windows: "));
        assertFalse(guided.systemPrompt(limits, Guidance.DetailLevel.DETAILED).contains("- windows: "));
    }

    @Test void editContextSurvivesReviewShrinkAndRelayRoundTrips() throws Exception {
        PromptSpec edit = PromptSpec.edit("minecraft:overworld", "minecraft:plains", "a stone house",
                List.of("add an east wing", "replace the roof with spruce"), PLAN, "furnish the bedroom",
                List.of(new Exchange("Which bed?", "red")), 0, 50000);
        for (PromptSpec spec : List.of(edit.review(PLAN, 1, 2), edit.shrink(PLAN), edit.shrink(PLAN).review(PLAN, 2, 2))) {
            PromptSpec back = PromptSpec.parse(spec.toJson());
            assertEquals(edit.originalRequest(), back.originalRequest());
            assertEquals(edit.earlierChanges(), back.earlierChanges());
            assertEquals(edit.request(), back.request());
            assertEquals(edit.exchanges(), back.exchanges());
            assertEquals(spec.userContent(), back.userContent());
            assertTrue(back.userContent().contains("Original build request: a stone house"));
            assertTrue(back.userContent().contains("replace the roof with spruce"));
            assertTrue(back.userContent().contains("COMPLETE replacement plan"));
        }
        for (PromptSpec spec : List.of(newBuild().review(PLAN, 1, 1), newBuild().shrink(PLAN))) {
            assertEquals(newBuild().terrain().format(), spec.terrain().format());
            assertTrue(PromptSpec.parse(spec.toJson()).userContent().contains(spec.terrain().format()));
            assertFalse(spec.userContent().contains("This is an edit"));
        }
    }

    @Test
    void jsonRoundTrips() throws Exception {
        for (PromptSpec spec : List.of(newBuild(), newBuild().review(PLAN, 2, 2), newBuild().shrink(PLAN),
                PromptSpec.edit("d:d", "b:b", "a hut", List.of(), PLAN, "add a door", List.of(), 0, 10))) {
            PromptSpec back = PromptSpec.parse(spec.toJson());
            assertEquals(spec.userContent(), back.userContent(), spec.kind().toString());
        }
        PromptSpec guided = newBuild().withGuides(false, List.of("windows"));
        PromptSpec back = PromptSpec.parse(guided.toJson());
        assertEquals(guided.guides(), back.guides());
        assertEquals(guided.userContent(), back.userContent());
        assertTrue(PromptSpec.parse(newBuild().withGuides(true, List.of()).toJson()).offerGuides());
        assertThrows(PromptSpec.SpecException.class, () -> PromptSpec.parse("{\"kind\":\"poem\"}"));
        assertThrows(PromptSpec.SpecException.class, () -> PromptSpec.parse("nope"));
    }

    @Test
    void revisionTextMatchesPromptBuilder() throws Exception {
        assertEquals(PromptBuilder.revisionContent(new BuildContext("minecraft:overworld", "minecraft:plains", null,
                Guidance.Style.NORDIC.promptLine(), null), new EditContext("a fishing hut", List.of("steeper roof"), PLAN,
                "add a chimney"), List.of(new Exchange("Brick?", "yes")), 1, terrain()), revision().userContent());
    }

    @Test
    void revisionsCarryTheProposalFlagThroughJsonAndCopies() throws Exception {
        PromptSpec revision = revision();
        assertEquals(PromptSpec.Kind.EDIT, revision.kind());
        assertTrue(revision.proposal());
        assertTrue(revision.toJson().get("proposal").getAsBoolean());
        assertFalse(PromptSpec.edit("d:d", "b:b", "a hut", List.of(), PLAN, "add a door", List.of(), 0, 10).toJson()
                .has("proposal"), "ordinary requests never carry the field");
        PromptSpec back = PromptSpec.parse(revision.toJson());
        assertTrue(back.proposal());
        assertEquals(revision.userContent(), back.userContent());
        var older = revision.toJson();
        older.remove("proposal");
        assertFalse(PromptSpec.parse(older).proposal(), "JSON without the field is an ordinary edit");
        for (PromptSpec copy : List.of(revision.review(PLAN, 1, 2), revision.shrink(PLAN),
                revision.withGuides(false, List.of("windows")), revision.withPlan(PLAN),
                revision.withFeedback(dev.justbuild.prompt.PlanningFeedback.EMPTY),
                revision.withTheme(dev.justbuild.prompt.ProjectTheme.EMPTY),
                revision.withAreas(dev.justbuild.prompt.PlanningAreas.EMPTY),
                revision.withConstraints(dev.justbuild.prompt.BuildConstraints.NONE),
                revision.withWorld(null, List.of(), 0))) {
            assertTrue(copy.proposal(), copy.kind().toString());
        }
    }

    @Test
    void proposalReviewsAndShrinksSayThePlanIsNotPlaced() throws Exception {
        for (PromptSpec spec : List.of(revision().review(PLAN, 1, 1), revision().shrink(PLAN))) {
            String text = PromptSpec.parse(spec.toJson()).userContent();
            assertTrue(text.contains("This revises a proposed build that has not been placed yet."), spec.kind().toString());
            assertTrue(text.contains("Requested change: add a chimney"), spec.kind().toString());
            assertFalse(text.contains("This is an edit to an existing build."), spec.kind().toString());
            assertFalse(text.contains("existing build"), spec.kind().toString());
            assertFalse(text.contains("Omitted cells"), spec.kind().toString());
        }
        String edit = PromptSpec.edit("d:d", "b:b", "a hut", List.of(), PLAN, "add a door", List.of(), 0, 10)
                .review(PLAN, 1, 1).userContent();
        assertTrue(edit.contains("This is an edit to an existing build."), "ordinary edit reviews keep their wording");
    }

    @Test
    void proposalSystemPromptAndLiveSampleFooterSayThePlanIsNotPlaced() throws Exception {
        dev.justbuild.plan.PlanLimits limits = new dev.justbuild.plan.PlanLimits(2000, 50000, 96);
        assertTrue(revision().systemPrompt(limits, Guidance.DetailLevel.DETAILED)
                .contains("revising a proposed build that has not been placed yet"));
        var snapshot = dev.justbuild.prompt.WorldSnapshot.sample(new dev.justbuild.plan.InspectionRequest(
                new Cell(0, 0, 0), new Cell(2, 1, 1)), p -> "minecraft:stone");
        String proposal = revision().withWorld(null, List.of(snapshot), 0).userContent();
        assertTrue(proposal.contains("These live samples show the site before the proposed build is placed; fit the plan "
                + "to them. Later overlapping samples supersede earlier samples."));
        assertFalse(proposal.contains("Preserve unrelated work."));
        String edit = PromptSpec.edit("d:d", "b:b", "a hut", List.of(), PLAN, "add a door", List.of(), 0, 10)
                .withWorld(null, List.of(snapshot), 0).userContent();
        assertTrue(edit.contains("the saved plan describes intent and may differ from the world. Preserve unrelated work."));
        assertFalse(edit.contains("before the proposed build is placed"));
    }
}
