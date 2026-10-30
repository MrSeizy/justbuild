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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PromptSpecTest {
    @BeforeAll
    static void bootstrap() {
        dev.justbuild.world.McBootstrap.init();
    }

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

    static dev.justbuild.plan.Critique critique() throws Exception {
        var plan = ((dev.justbuild.plan.PlanResponse.Build) dev.justbuild.plan.PlanParser.parse(PLAN)).plan();
        return dev.justbuild.plan.CritiqueParser.parse("{\"kind\":\"critique\",\"verdict\":\"revise\",\"problems\":["
                + "{\"severity\":\"should_fix\",\"category\":\"access\",\"operations\":[0],\"cell\":null,"
                + "\"problem\":\"The door is blocked.\",\"fix\":\"Clear the cell in front of the door.\"}],"
                + "\"keep\":[\"the stone floor\"]}", plan, PromptCheck.planKey(PLAN));
    }

    static PromptSpec base() {
        return PromptSpec.newBuild("minecraft:overworld", "minecraft:plains", PromptSpec.Placement.FRONT, "nordic",
                null, "a fishing hut", List.of(), 2, terrain(), 50000);
    }

    @Test
    void critiqueRoundsAskNothingAndRoundTrip() throws Exception {
        PromptSpec spec = base().withWorld(null, List.of(), 2).withGuides(true, List.of("windows"))
                .critiqueRound(PLAN, 1, 2, null);
        assertEquals(PromptSpec.Kind.CRITIQUE, spec.kind());
        assertEquals(0, spec.remaining());
        assertEquals(0, spec.inspectionRemaining());
        assertFalse(spec.offerGuides());
        assertEquals(List.of("windows"), spec.guides(), "selected references survive into the critique");
        assertNull(spec.critique());
        assertEquals(dev.justbuild.plan.CritiqueSchema.JSON, spec.schemaJson());
        assertEquals(dev.justbuild.plan.BuildPlanSchema.JSON, base().schemaJson());
        String json = spec.toJson().toString();
        assertEquals(json, PromptSpec.parse(json).toJson().toString());
        assertFalse(spec.toJson().has("critique"), "no note is written when there is none");
        assertFalse(base().toJson().has("critique"));
        assertFalse(base().review(PLAN, 1, 1).toJson().has("critique"));
    }

    @Test
    void critiqueNotesSurviveEveryCopy() throws Exception {
        var note = new dev.justbuild.plan.CritiqueNote(critique(), dev.justbuild.plan.RepairOutcome.REJECTED_ROUTE);
        PromptSpec spec = base().critiqueRound(PLAN, 2, 2, note);
        for (PromptSpec copy : List.of(spec.withGuides(false, List.of()), spec.withFeedback(dev.justbuild.prompt.PlanningFeedback.EMPTY),
                spec.withPlan(PLAN), spec.withWorld(null, List.of(), 0), spec.withAreas(dev.justbuild.prompt.PlanningAreas.EMPTY),
                spec.withTheme(dev.justbuild.prompt.ProjectTheme.EMPTY), spec.withConstraints(dev.justbuild.prompt.BuildConstraints.NONE),
                PromptSpec.parse(spec.toJson().toString()))) {
            assertEquals(note, copy.critique());
        }
        var repair = new dev.justbuild.plan.CritiqueNote(critique(), null);
        assertEquals(repair, base().review(PLAN, 1, 1, repair).critique());
        assertEquals(repair, PromptSpec.parse(base().review(PLAN, 1, 1, repair).toJson().toString()).critique());
        assertNull(base().review(PLAN, 1, 1).critique(), "the builder's own review carries no critique");
        assertNull(base().review(PLAN, 1, 1, repair).shrink(PLAN).critique(), "a smaller-plan request never carries one");
    }

    @Test
    void critiqueAndRepairRequestsGetTheFacades() throws Exception {
        var volume = dev.justbuild.prompt.VoxelVolume.capture(new dev.justbuild.plan.InspectionRequest(
                new dev.justbuild.plan.Cell(0, 0, 0), new dev.justbuild.plan.Cell(0, 0, 0)), 1, true, c -> "minecraft:stone");
        var feedback = new dev.justbuild.prompt.PlanningFeedback(PromptCheck.planKey(PLAN), List.of(), volume, volume, null, null);
        java.util.function.Function<PromptSpec, Integer> images = spec -> new dev.justbuild.provider.PlanRequest("S", "U",
                spec.schemaJson(), "", java.time.Duration.ofSeconds(1), spec.withFeedback(feedback)).images().size();
        assertEquals(2, images.apply(base().review(PLAN, 1, 1)));
        assertEquals(3, images.apply(base().critiqueRound(PLAN, 1, 1, null)));
        assertEquals(3, images.apply(base().review(PLAN, 1, 1, new dev.justbuild.plan.CritiqueNote(critique(), null))));
    }

    @Test
    void malformedNotesAreSpecErrors() throws Exception {
        var spec = base().critiqueRound(PLAN, 1, 1, null);
        var o = spec.toJson();
        var bad = new com.google.gson.JsonObject();
        bad.addProperty("critique", "nope");
        o.add("critique", bad);
        assertThrows(PromptSpec.SpecException.class, () -> PromptSpec.parse(o));
        var withNote = base().critiqueRound(PLAN, 2, 2,
                new dev.justbuild.plan.CritiqueNote(critique(), dev.justbuild.plan.RepairOutcome.REJECTED_ROUTE)).toJson();
        withNote.getAsJsonObject("critique").addProperty("outcome", "BOGUS");
        assertThrows(PromptSpec.SpecException.class, () -> PromptSpec.parse(withNote));
    }

    @Test
    void critiqueRoundsCarryTerrainAcrossTheRelay() throws Exception {
        PromptSpec spec = base().critiqueRound(PLAN, 1, 1, null);
        assertTrue(spec.userContent().contains(terrain().format()));
        assertEquals(spec.userContent(), PromptSpec.parse(spec.toJson().toString()).userContent());
    }

    @Test
    void theFacadeSentenceFollowsTheAttachedImage() throws Exception {
        var volume = dev.justbuild.prompt.VoxelVolume.capture(new dev.justbuild.plan.InspectionRequest(
                new dev.justbuild.plan.Cell(0, 0, 0), new dev.justbuild.plan.Cell(0, 0, 0)), 1, true, c -> "minecraft:stone");
        var feedback = new dev.justbuild.prompt.PlanningFeedback(PromptCheck.planKey(PLAN), List.of(), volume, volume, null, null);
        String sentence = "four facades seen straight on";
        PromptSpec critique = base().critiqueRound(PLAN, 1, 1, null).withFeedback(feedback);
        assertTrue(critique.facades() && critique.userContent().contains(sentence));
        PromptSpec bare = base().critiqueRound(PLAN, 1, 1, null);
        assertFalse(bare.facades() || bare.userContent().contains(sentence) || bare.userContent().contains("four facades"));
        PromptSpec self = base().review(PLAN, 1, 1).withFeedback(feedback);
        assertFalse(self.facades() || self.userContent().contains("facades"), "a self-review never mentions facades");
        var note = new dev.justbuild.plan.CritiqueNote(critique(), null);
        PromptSpec repair = base().review(PLAN, 1, 1, note).withFeedback(feedback);
        assertTrue(repair.facades() && repair.userContent().contains(sentence));
    }

    @Test
    void theCriticSeesWhoSelectedTheGuides() throws Exception {
        PromptSpec critique = base().withGuides(false, List.of("windows")).critiqueRound(PLAN, 1, 1, null);
        assertTrue(critique.userContent().contains("Guides the builder selected"));
        assertFalse(critique.userContent().contains("Guides selected by you"));
        assertTrue(newBuild().withGuides(false, List.of("windows")).userContent().contains("Guides selected by you"));
    }
}
