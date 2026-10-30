package dev.justbuild.relay;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.plan.PlanException;
import dev.justbuild.plan.PlanParser;
import dev.justbuild.plan.PlanResponse;
import dev.justbuild.plan.PlanSanitizer;
import dev.justbuild.plan.PlanWriter;
import dev.justbuild.prompt.Exchange;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RelayOutputTest {
    @BeforeAll
    static void registries() {
        dev.justbuild.world.McBootstrap.init();
    }

    @Test
    void plansCarryOnlyRealBlocksBackToTheServer() throws Exception {
        RelayOutput out = RelayOutput.normalize(PromptSpecTest.PLAN.replace("minecraft:stone",
                "The player's key is sk-123 and their home folder holds notes.txt"));
        assertFalse(out.json().contains("sk-123"), out.json());
        assertTrue(out.json().contains(PlanSanitizer.UNKNOWN_BLOCK));
        assertEquals(PromptCheck.planKey(out.json()), out.planKey());
    }

    @Test
    void theServersEchoOfAPlanHasTheSameKey() throws Exception {
        String raw = "{\"kind\":\"build\",\"title\":\"Hall\",\"summary\":\"A hall\",\"operations\":["
                + "{\"op\":\"fill\",\"block\":\"Stone_Brick\",\"from\":[0,0,0],\"to\":[4,3,6],\"hollow\":true},"
                + "{\"op\":\"roof\",\"block\":\"oak_planks\",\"trim\":\"oak_log\",\"from\":[0,4,0],\"to\":[4,6,6],"
                + "\"mix\":[\"stone\"],\"finial\":[\"torch\"]},"
                + "{\"op\":\"set\",\"block\":\"torch\",\"from\":[1,1,1],\"repeat\":{\"count\":3,\"step\":[1,0,0]}}]}";
        RelayOutput out = RelayOutput.normalize(raw);
        String echoed = PlanWriter.write(((PlanResponse.Build) PlanParser.parse(out.json())).plan());
        assertEquals(out.planKey(), PromptCheck.planKey(echoed));
    }

    @Test
    void buildsAreRewrittenWithoutExtraFields() throws Exception {
        String raw = "```json\n" + PromptSpecTest.PLAN.replace("\"title\":\"Hut\"",
                "\"title\":\"Hut\",\"secret\":\"exfiltrated text\"") + "\n```";
        RelayOutput out = RelayOutput.normalize(raw);
        assertNull(out.question());
        assertFalse(out.json().contains("exfiltrated"));
        assertInstanceOf(PlanResponse.Build.class, PlanParser.parse(out.json()));
    }

    @Test
    void questionsAndDeclinesKeepOnlyTheirFields() throws Exception {
        RelayOutput q = RelayOutput.normalize("{\"kind\":\"question\",\"question\":\"How tall?\",\"options\":[\"5\",\"9\"],"
                + "\"operations\":[{\"op\":\"set\",\"block\":\"lots of text\"}]}");
        assertEquals("How tall?", q.question());
        assertFalse(q.json().contains("lots of text"));
        assertInstanceOf(PlanResponse.Question.class, PlanParser.parse(q.json()));
        RelayOutput d = RelayOutput.normalize("{\"kind\":\"decline\",\"summary\":\"I can't build that.\"}");
        assertInstanceOf(PlanResponse.Declined.class, PlanParser.parse(d.json()));
        assertThrows(PlanException.class, () -> RelayOutput.normalize("Sure! Here is your poem: roses are red"));
        RelayOutput g = RelayOutput.normalize("{\"kind\":\"guides\",\"guides\":[\"windows\",\"made-up\"],"
                + "\"summary\":\"lots of text\"}");
        assertFalse(g.json().contains("lots of text") || g.json().contains("made-up"));
        assertEquals(List.of("windows"), ((PlanResponse.Guides) PlanParser.parse(g.json())).ids());
        assertNull(g.question());
    }

    @Test
    void sixSelectedReferencesSurviveThePlayerRelay() throws Exception {
        var output = RelayOutput.normalize("""
                {"kind":"guides","guides":["castles","towers","interiors","landscaping","glowing-ceilings","chimneys"]}
                """);
        assertEquals(List.of("castles", "towers", "interiors", "landscaping", "glowing-ceilings", "chimneys"),
                assertInstanceOf(PlanResponse.Guides.class, PlanParser.parse(output.json())).ids());
    }

    @Test
    void unknownGuideNamesCannotCarryPrivateText() {
        String secret = "private-instructions-from-home";
        PlanException failure = assertThrows(PlanException.class, () -> RelayOutput.normalize(
                "{\"kind\":\"guides\",\"guides\":[\"" + secret + "\"]}"));
        assertFalse(failure.getMessage().contains(secret));
    }

    @Test
    void declineReasonsStayInThePlayersGame() throws Exception {
        String secret = "private instruction canary";
        RelayOutput output = RelayOutput.normalize("{\"kind\":\"decline\",\"summary\":\"" + secret + "\"}");
        assertFalse(output.json().contains(secret));
        assertTrue(output.localMessage().contains(secret));
        assertEquals(RelayOutput.DECLINED, ((PlanResponse.Declined) PlanParser.parse(output.json())).reason());
    }

    @Test
    void freeTextIsBoundedVisibleAndStillSupportsInternationalNames() throws Exception {
        var raw = com.google.gson.JsonParser.parseString(PromptSpecTest.PLAN).getAsJsonObject();
        raw.addProperty("title", "城\u202e 堡\u200b");
        raw.addProperty("summary", "é".repeat(400));
        RelayOutput output = RelayOutput.normalize(raw.toString());
        var plan = ((PlanResponse.Build) PlanParser.parse(output.json())).plan();
        assertEquals("城 堡", plan.title());
        assertEquals(240, plan.summary().length());
        assertFalse(output.json().contains("\u202e"));
        assertFalse(output.json().contains("\u200b"));
        assertEquals(output.planKey(), PromptCheck.planKey(PlanWriter.write(plan)));

        String questions = "{\"kind\":\"question\",\"question\":\"" + "Where? ".repeat(50)
                + "\",\"options\":[\"" + "A".repeat(80) + "\",\"\u202e\",\"小屋\"]}";
        PlanResponse.Question question = (PlanResponse.Question) PlanParser.parse(RelayOutput.normalize(questions).json());
        assertTrue(question.question().length() <= 240);
        assertEquals(List.of("A".repeat(60), "小屋"), question.options());
    }

    @Test
    void questionProvenanceUsesExactlyTheNormalizedTextTheServerReceives() throws Exception {
        String question = "How tall?\u202e " + "Pick a size. ".repeat(30);
        var raw = new com.google.gson.JsonObject();
        raw.addProperty("kind", "question");
        raw.addProperty("question", question);
        RelayOutput output = RelayOutput.normalize(raw.toString());
        PlanResponse.Question received = (PlanResponse.Question) PlanParser.parse(output.json());
        assertEquals(output.question(), received.question());
        PromptCheck.Seen seen = new PromptCheck.Seen(Set.of("a fishing hut"), Set.of(output.question()), Set.of("small"));
        var followup = PromptCheckTest.spec("a fishing hut", List.of(new Exchange(received.question(), "small")));
        assertNull(PromptCheck.check(followup, seen).problem());
        assertThrows(PlanException.class, () -> RelayOutput.normalize("{\"kind\":\"question\",\"question\":\"\u202e\u200b\"}"));
    }

    @Test
    void malformedModelFieldsCannotEscapeInThePublicDiagnostic() {
        String secret = "private instruction canary";
        for (String raw : List.of("{\"kind\":\"" + secret + "\"}",
                PromptSpecTest.PLAN.replace("\"fill\"", "\"" + secret + "\""))) {
            PlanException original = assertThrows(PlanException.class, () -> PlanParser.parse(raw));
            assertTrue(original.getMessage().contains(secret), "reproduces the parser's raw-text diagnostic");
            PlanException outbound = assertThrows(PlanException.class, () -> RelayOutput.normalize(raw));
            assertFalse(outbound.getMessage().contains(secret));
            assertTrue(outbound.getCause().getMessage().contains(secret), "detail remains available to the local player");
        }
    }

    @Test
    void critiquesAreRewrittenToBoundedFindings() throws Exception {
        PromptSpec spec = PromptSpecTest.base().critiqueRound(PromptSpecTest.PLAN, 1, 1, null);
        String raw = "```json\n{\"kind\":\"critique\",\"verdict\":\"revise\",\"secret\":\"exfiltrated text\",\"problems\":["
                + "{\"severity\":\"must_fix\",\"category\":\"request\",\"operations\":[0, 7],\"cell\":null,"
                + "\"problem\":\"No door.\",\"fix\":\"Add an oak door.\",\"extra\":\"sk-123\"}],\"keep\":[]}\n```";
        RelayOutput out = RelayOutput.critique(raw, spec);
        assertFalse(out.json().contains("exfiltrated") || out.json().contains("sk-123"), out.json());
        assertNull(out.planKey());
        assertNull(out.question());
        var echoed = dev.justbuild.plan.CritiqueParser.fromJson(
                com.google.gson.JsonParser.parseString(out.json()).getAsJsonObject());
        assertEquals(out.critiqueKey(), echoed.key(), "the server's echo has the same key");
        assertEquals(PromptCheck.planKey(PromptSpecTest.PLAN), echoed.planKey());
        assertEquals(java.util.List.of(0), echoed.problems().get(0).operations());
        assertThrows(dev.justbuild.plan.PlanException.class, () -> RelayOutput.critique(PromptSpecTest.PLAN, spec),
                "a plan is not a critique");
        assertNull(RelayOutput.normalize(PromptSpecTest.PLAN).critiqueKey());
    }

    @Test
    void malformedCritiqueFieldsCannotEscapeInThePublicDiagnostic() throws Exception {
        PromptSpec spec = PromptSpecTest.base().critiqueRound(PromptSpecTest.PLAN, 1, 1, null);
        String raw = "{\"kind\":\"critique\",\"verdict\":\"revise\",\"problems\":["
                + "{\"severity\":\"SECRET-CANARY\",\"category\":\"request\",\"operations\":[0],\"cell\":null,"
                + "\"problem\":\"No door.\",\"fix\":\"Add a door.\"}],\"keep\":[]}";
        PlanException outbound = assertThrows(PlanException.class, () -> RelayOutput.critique(raw, spec));
        assertFalse(outbound.getMessage().contains("SECRET-CANARY"), outbound.getMessage());
    }

    @Test
    void critiqueFixesAreTruncated() throws Exception {
        PromptSpec spec = PromptSpecTest.base().critiqueRound(PromptSpecTest.PLAN, 1, 1, null);
        String raw = "{\"kind\":\"critique\",\"verdict\":\"revise\",\"problems\":["
                + "{\"severity\":\"must_fix\",\"category\":\"request\",\"operations\":[0],\"cell\":null,"
                + "\"problem\":\"No door.\",\"fix\":\"" + "x".repeat(300) + "\"}],\"keep\":[]}";
        RelayOutput out = RelayOutput.critique(raw, spec);
        var problem = com.google.gson.JsonParser.parseString(out.json()).getAsJsonObject()
                .getAsJsonArray("problems").get(0).getAsJsonObject();
        assertEquals("x".repeat(240), problem.get("fix").getAsString());
    }
}
