package dev.justbuild.prompt;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.plan.PlanLimits;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptBuilderTest {
    @Test
    void systemPromptStatesFrameAndLimits() {
        String prompt = PromptBuilder.systemPrompt(new PlanLimits(123, 4567, 89));
        assertTrue(prompt.contains("at most 123 operations, at most 4567 blocks in total, and at most 89 blocks"));
        assertTrue(prompt.contains("facing=south faces the player"));
        assertTrue(prompt.contains("+z is south"));
        assertFalse(prompt.contains("%d"));
    }

    @Test
    void guideMenuOnlyWhenOffered() {
        PlanLimits limits = new PlanLimits(2000, 50000, 96);
        String offered = PromptBuilder.systemPrompt(limits, Guidance.DetailLevel.DETAILED, true);
        String plain = PromptBuilder.systemPrompt(limits, Guidance.DetailLevel.DETAILED, false);
        assertTrue(offered.contains("kind \"guides\""));
        assertTrue(offered.contains("- timber-frame: "));
        assertTrue(offered.contains("World inspection is not available"));
        assertFalse(plain.contains("timber-frame"));
        assertEquals(plain, PromptBuilder.systemPrompt(limits, Guidance.DetailLevel.DETAILED));
    }

    @Test
    void withGuidesAppendsTheirTexts() {
        GuideLibrary.Guide windows = GuideLibrary.defaults().byId("windows").orElseThrow();
        String content = PromptBuilder.withGuides("Request: a cottage", List.of(windows));
        assertTrue(content.startsWith("Request: a cottage\n\nGuides selected by you"));
        assertTrue(content.contains("attached: windows"));
        assertTrue(content.contains("## " + windows.title() + "\n" + windows.text()));
        assertEquals("Request: a cottage", PromptBuilder.withGuides("Request: a cottage", List.of()));
    }

    @Test
    void systemPromptTeachesHowBigVolumesGet() {
        String prompt = PromptBuilder.systemPrompt(new PlanLimits(2000, 50000, 96));
        assertTrue(prompt.contains("width × height × depth"));
        assertTrue(prompt.contains("including the air that clears the inside of hollow shapes"));
        assertTrue(prompt.contains("well under 50000 blocks"));
    }

    @Test
    void constructionReferenceSelectionDoesNotPrescribeOneLook() {
        String prompt = PromptBuilder.systemPrompt(new PlanLimits(2000, 50000, 96), Guidance.DetailLevel.DETAILED, true);
        assertTrue(prompt.contains("main subject and required functions"));
        assertTrue(prompt.contains("one guide-selection"));
        assertTrue(prompt.contains("For a simple change"), "trivial edits can skip references");
        assertTrue(prompt.contains("\"rim\" false"), "technical roof controls remain documented");
        assertTrue(prompt.contains("last stair is h-1"), "precise stair landing semantics are retained");
        assertFalse(prompt.contains("Matching guides are attached"));
        assertFalse(prompt.contains("suit old and fantasy builds"), "style recipes live in references");
    }

    @Test
    void shrinkContentGivesTheCountTheLimitAndThePlan() {
        String content = PromptBuilder.shrinkContent(new BuildContext("d", "b"), "a giant castle", null, List.of(), List.of(),
                "{\"kind\":\"build\"}", "about 61,200", 50000);
        assertTrue(content.contains("Player request: a giant castle"));
        assertTrue(content.contains("places about 61,200 blocks"));
        assertTrue(content.contains("limit of 50000"));
        assertTrue(content.contains("{\"kind\":\"build\"}"));
        assertTrue(content.contains("separate walls, floors, and roofs"));
        assertTrue(content.contains("Do not ask a question"));
    }

    @Test
    void userContentHasContextRequestAndTranscript() {
        String content = PromptBuilder.userContent(new BuildContext("minecraft:overworld", "minecraft:plains"),
                " a tower ", List.of(new Exchange("How tall?", "Very"), new Exchange("Stone or wood?", "Stone")), 1);
        assertEquals("""
                Context:
                - Dimension: minecraft:overworld
                - Biome: minecraft:plains
                - Questions remaining: 1

                Player request: a tower
                You asked: How tall?
                Player answered: Very
                You asked: Stone or wood?
                Player answered: Stone""", content);
    }

    @Test
    void contextWarnsAboutBedsWhereTheyExplode() {
        for (String dimension : List.of("minecraft:the_nether", "minecraft:the_end")) {
            String content = PromptBuilder.userContent(new BuildContext(dimension, "minecraft:plains"), "a house",
                    List.of(), 1);
            assertTrue(content.contains("- Dimension: " + dimension + "\n- Dimension rules: beds explode"), dimension);
        }
        String overworld = PromptBuilder.userContent(new BuildContext("minecraft:overworld", "minecraft:plains"),
                "a house", List.of(), 1);
        assertFalse(overworld.contains("Dimension rules"));
    }

    @Test
    void systemPromptLeadsWithSafetyRulesAndDocumentsEveryTool() {
        String prompt = PromptBuilder.systemPrompt(new PlanLimits(1, 2, 3));
        assertTrue(prompt.startsWith("Rules (these always apply"));
        assertTrue(prompt.contains("You have no host tools. Never try to read, write, or access files"));
        assertTrue(prompt.contains("Refuse sexual or nude content and hate symbols"));
        assertTrue(prompt.contains("kind \"decline\""));
        for (dev.justbuild.plan.OpType type : dev.justbuild.plan.OpType.values()) {
            assertTrue(prompt.contains("- " + type.id() + ":"), type.id());
        }
        assertTrue(prompt.contains("- repeat:"));
        assertTrue(prompt.contains("- mirror:"));
        assertTrue(prompt.contains("Editing:"));
        assertTrue(prompt.contains("Terrain:"));
    }

    @Test
    void terrainGridIsIncludedForNewBuilds() {
        var terrain = new TerrainSummary(2, -2, -4, new int[][] {{0, 1, TerrainSummary.UNKNOWN}, {-1, -1, -1}, {-1, -2, -1}},
                new java.util.LinkedHashMap<>(java.util.Map.of("grass_block", 7)));
        String content = PromptBuilder.userContent(new BuildContext("d", "b"), "a hut", List.of(), 3, terrain);
        assertTrue(content.contains("one row per z from -4 (far) to 0 (near); columns are x = -2 to 2"));
        assertTrue(content.contains("\nz=-4: 0 1 ?\nz=-2: -1 -1 -1\nz=0: -1 -2 -1"));
        assertTrue(content.contains("Surface blocks: grass_block 7"));
        assertTrue(content.indexOf("Terrain") < content.indexOf("Player request: a hut"));
    }

    @Test
    void editContentCarriesHistoryAndCurrentPlan() {
        String content = PromptBuilder.editContent(new BuildContext("d", "b"),
                new EditContext("a castle", List.of("add a moat"), "{\"kind\":\"build\"}", " make the towers taller "),
                List.of(new Exchange("How much taller?", "Double")), 2);
        assertTrue(content.contains("You are editing an existing build.\nOriginal request: a castle"
                + "\nEarlier changes:\n- add a moat\nCurrent plan:\n{\"kind\":\"build\"}"
                + "\n\nRequested change: make the towers taller\nYou asked: How much taller?\nPlayer answered: Double"));
    }

    @Test
    void revisionContentDescribesAnUnplacedProposalWithItsSite() {
        TerrainSummary terrain = new TerrainSummary(4, -8, -16, new int[][] {{-1, 0}}, Map.of("minecraft:grass_block", 2));
        String content = PromptBuilder.revisionContent(new BuildContext("d", "b"),
                new EditContext("a hut", List.of("steeper roof"), "{plan}", " add a chimney "),
                List.of(new Exchange("Brick?", "yes")), 1, terrain);
        assertTrue(content.contains(terrain.format()), "the site's terrain is included");
        assertTrue(content.contains("You are revising a proposed build that has not been placed yet. "
                + "World samples show the site without it.\nOriginal request: a hut\nEarlier changes:\n- steeper roof"
                + "\nCurrent plan:\n{plan}\n\nRequested change: add a chimney\n"));
        assertTrue(content.contains("When you build, keep the plan's origin and return the complete revised plan,"));
        assertTrue(content.indexOf(terrain.format()) < content.indexOf("You are revising"), "terrain comes first");
        assertTrue(content.endsWith("You asked: Brick?\nPlayer answered: yes"));
        assertFalse(content.contains("existing build"));
    }

    @Test
    void proposalSystemPromptSaysThePlanIsNotPlaced() {
        PlanLimits limits = new PlanLimits(2000, 50000, 96);
        String proposal = PromptBuilder.systemPrompt(limits, Guidance.DetailLevel.DETAILED, false, 0, true);
        assertTrue(proposal.contains("you are revising a proposed build that has not been placed yet."));
        assertFalse(proposal.contains("revising an existing build"));
        String ordinary = PromptBuilder.systemPrompt(limits, Guidance.DetailLevel.DETAILED, false, 0);
        assertTrue(ordinary.contains("you are revising an existing build."));
        assertEquals(ordinary, PromptBuilder.systemPrompt(limits, Guidance.DetailLevel.DETAILED, false, 0, false));
    }

    @Test
    void ordinaryEditReviewKeepsItsExactWording() {
        String content = PromptBuilder.reviewContent(new BuildContext("d", "b"), "add a door", "a hut", List.of(),
                List.of(), "{plan}", "views", 1, 1);
        assertTrue(content.contains("\nThis is an edit to an existing build.\nOriginal build request: a hut\n"
                + "Requested change: add a door\nThe candidate below is the COMPLETE replacement plan for the existing "
                + "build, including unchanged parts. Omitted cells revert to their pre-build state; returning only "
                + "furniture or a patch can erase the walls, floors, roof and doors. Preserve existing structure the "
                + "draft retains, keep the removals already made for the requested edit, and keep usable entrances and "
                + "routes. Review may refine the candidate, not start a different edit."));
    }

    @Test
    void placementLinesForFrontAndTargetedBuilds() {
        assertEquals("(0, 0, 0) is at the player's feet level, two blocks in front of them; the player stands at "
                + "(0, 0, 2). Put the build in front of the player (z <= 0), roughly centered on x = 0.",
                PromptBuilder.frontPlacement());
        assertEquals("the player is pointing at the build site 23 blocks away; (0, 0, 0) is the first layer above the "
                + "targeted ground. Center the build on x = 0, z = 0. The player stands at (1, 3, 23); keep the build "
                + "at least 2 blocks away from them.", PromptBuilder.targetPlacement(23, new dev.justbuild.plan.Cell(1, 3, 23)));
        String content = PromptBuilder.userContent(new BuildContext("d", "b", PromptBuilder.frontPlacement()), "x",
                List.of(), 1);
        assertTrue(content.contains("\n- Placement: (0, 0, 0) is at the player's feet level"));
        String prompt = PromptBuilder.systemPrompt(new PlanLimits(1, 2, 3));
        assertTrue(prompt.contains("The context says where (0, 0, 0) is and where the player stands."));
        assertTrue(prompt.contains("Never put blocks where the player stands"));
        assertTrue(prompt.contains("Do not use fire, lava, or TNT"));
    }

    @Test
    void detailLevelAndQualityRulesInTheSystemPrompt() {
        String quick = PromptBuilder.systemPrompt(new PlanLimits(1, 2, 3), Guidance.DetailLevel.QUICK);
        String master = PromptBuilder.systemPrompt(new PlanLimits(1, 2, 3), Guidance.DetailLevel.MASTERPIECE);
        assertTrue(quick.contains("Detail level: quick."));
        assertTrue(master.contains("Detail level: masterpiece."));
        for (String prompt : new String[] {quick, master}) {
            assertFalse(prompt.contains("Never leave a large wall blank"));
            assertFalse(prompt.contains("Never a bare pyramid roof"));
            assertTrue(prompt.contains("restrained designs are valid"));
        }
    }

    @Test
    void styleAndDirectionLinesInTheContext() {
        var context = new BuildContext("d", "b", null, "japanese, suggested palette: dark oak", "tall and narrow");
        String content = PromptBuilder.userContent(context, "a tower", List.of(), 1);
        assertTrue(content.contains("\n- Style: japanese, suggested palette: dark oak"));
        assertTrue(content.contains("\n- Creative direction (the player's words win where they conflict): tall and narrow"));
    }

    @Test
    void reviewContentCarriesPlanViewsAndRound() {
        String content = PromptBuilder.reviewContent(new BuildContext("d", "b"), "a hut", null, List.of(), List.of(),
                "{\"kind\":\"build\"}", "Legend: a = minecraft:stone", 1, 2);
        assertTrue(content.contains("You are reviewing your own plan before it is built (round 1 of 2)."));
        assertTrue(content.contains("Player request: a hut"));
        assertTrue(content.contains("Current plan:\n{\"kind\":\"build\"}"));
        assertTrue(content.contains("Views of the plan:\nLegend: a = minecraft:stone"));
        assertTrue(content.contains("Return the complete improved plan with kind \"build\""));
        assertTrue(content.contains("a window on a roof slope needs a dormer around it"),
                "reviews don't stick a lone window on the roof as decoration");
        assertTrue(content.contains("they do not show textures, lighting, or in-game appearance"),
                "design critique stays within what the simplified views show");
        assertTrue(content.contains("sparse detail, and empty space can be deliberate"),
                "the critique doesn't turn restraint into a defect");
        assertTrue(content.contains("Questions remaining: 0"));
    }

    @Test
    void zeroQuestionsRemainingDemandsABuild() {
        String content = PromptBuilder.userContent(new BuildContext("d", "b"), "x", List.of(), 0);
        assertTrue(content.contains("Questions remaining: 0 (no more clarifying questions; use offered tools or return the build)"));
    }
}
