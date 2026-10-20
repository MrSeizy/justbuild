package dev.justbuild.plan;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PlanParserTest {
    static final String CLAUDE_SPIKE = """
            {"kind":"build","question":null,"options":null,"title":"Tiny Stone Platform",
             "summary":"A 3x3 stone platform at y=0 with a torch in the center on top.",
             "operations":[
               {"op":"fill","block":"stone","from":[0,0,0],"to":[2,0,2],"radius":null,"height":null,"hollow":null},
               {"op":"set","block":"torch","from":[1,1,1],"to":null,"radius":null,"height":null,"hollow":null}]}
            """;

    @Test
    void parsesBuildPlan() throws Exception {
        var build = assertInstanceOf(PlanResponse.Build.class, PlanParser.parse(CLAUDE_SPIKE));
        BuildPlan plan = build.plan();
        assertEquals("Tiny Stone Platform", plan.title());
        assertEquals(2, plan.operations().size());
        assertEquals(new Operation(OpType.FILL, "stone", new Cell(0, 0, 0), new Cell(2, 0, 2), null, null, false),
                plan.operations().get(0));
        assertEquals(Operation.set("torch", new Cell(1, 1, 1)), plan.operations().get(1));
    }

    /** Cast to int, these became 0 or negative after the positive check; a saved plan with count 0 never loads again. */
    @Test
    void refusesRepeatCountsThatDoNotFitAnInt() {
        for (String count : new String[] {"4294967296", "2147483648", "1e999"}) {
            String json = "{\"kind\":\"build\",\"title\":\"x\",\"operations\":[{\"op\":\"set\",\"block\":\"stone\","
                    + "\"from\":[0,1,-3],\"repeat\":{\"count\":" + count + ",\"step\":[0,0,0]}}]}";
            assertThrows(PlanException.class, () -> PlanParser.parse(json), count);
        }
    }

    @Test
    void parsesQuestionAndCapsOptions() throws Exception {
        String json = """
                {"kind":"question","question":" How big? ","options":[" Small ","Medium","Large","Huge","Giant",""],
                 "title":null,"summary":null,"operations":null}
                """;
        var q = assertInstanceOf(PlanResponse.Question.class, PlanParser.parse(json));
        assertEquals("How big?", q.question());
        assertEquals(java.util.List.of("Small", "Medium", "Large", "Huge"), q.options());
    }

    @Test
    void parsesGuideRequest() throws Exception {
        String json = """
                {"kind":"guides","question":null,"options":null,"title":null,"summary":null,"operations":null,
                 "guides":[" Timber-Frame ","windows","",7]}
                """;
        var g = assertInstanceOf(PlanResponse.Guides.class, PlanParser.parse(json));
        assertEquals(java.util.List.of("timber-frame", "windows", "7"), g.ids());
        assertMessage("\"guides\" is empty", "{\"kind\":\"guides\",\"guides\":[]}");
    }

    @Test
    void preservesSixSelectedGuidesAndBoundsLongerLists() throws Exception {
        var response = assertInstanceOf(PlanResponse.Guides.class, PlanParser.parse("""
                {"kind":"guides","guides":["castles","towers","interiors","landscaping","glowing-ceilings","chimneys","windows"]}
                """));
        assertEquals(java.util.List.of("castles", "towers", "interiors", "landscaping", "glowing-ceilings", "chimneys"),
                response.ids());
    }

    @Test
    void toleratesFencesAndProse() throws Exception {
        String wrapped = "Here you go:\n```json\n" + CLAUDE_SPIKE + "\n```\nEnjoy!";
        assertInstanceOf(PlanResponse.Build.class, PlanParser.parse(wrapped));
    }

    @Test
    void roundsNumbersAndDefaultsTitle() throws Exception {
        String json = """
                {"kind":"build","title":"  ","operations":[{"op":"SET","block":"stone","from":[1.0,2.4,-0.6]}]}
                """;
        BuildPlan plan = ((PlanResponse.Build) PlanParser.parse(json)).plan();
        assertEquals("Untitled build", plan.title());
        assertEquals("", plan.summary());
        assertEquals(new Cell(1, 2, -1), plan.operations().get(0).from());
    }

    @Test
    void rejectsBadInput() {
        assertMessage("missing \"kind\"", "{\"operations\":[]}");
        assertMessage("unknown kind", "{\"kind\":\"poem\"}");
        assertMessage("\"operations\" is empty", "{\"kind\":\"build\",\"operations\":[]}");
        assertMessage("\"question\" is empty", "{\"kind\":\"question\",\"question\":\"\"}");
        assertMessage("operation 1: unknown operation", op("{\"op\":\"spiral\",\"block\":\"stone\",\"from\":[0,0,0]}"));
        assertMessage("operation 1: fill needs \"to\"", op("{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0]}"));
        assertMessage("operation 1: sphere needs a positive \"radius\"", op("{\"op\":\"sphere\",\"block\":\"stone\",\"from\":[0,0,0],\"radius\":0}"));
        assertMessage("operation 1: cylinder needs \"height\"", op("{\"op\":\"cylinder\",\"block\":\"stone\",\"from\":[0,0,0],\"radius\":2}"));
        assertMessage("operation 1: \"from\" must be [x, y, z]", op("{\"op\":\"set\",\"block\":\"stone\",\"from\":[0,0]}"));
        assertMessage("operation 1: missing \"block\"", op("{\"op\":\"set\",\"from\":[0,0,0]}"));
        assertMessage("no JSON object", "I can't help with that.");
    }

    private static String op(String operation) {
        return "{\"kind\":\"build\",\"operations\":[" + operation + "]}";
    }

    private static void assertMessage(String expected, String json) {
        PlanException e = assertThrows(PlanException.class, () -> PlanParser.parse(json));
        assertTrue(e.getMessage().contains(expected), () -> "message was: " + e.getMessage());
    }
}
