package dev.justbuild.plan;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class CritiqueParserTest {
    static final String KEY = "a".repeat(64);
    static final BuildPlan PLAN = new BuildPlan("Hut", "A hut", List.of(
            Operation.set("minecraft:stone", new Cell(0, 0, 0)),
            Operation.set("minecraft:oak_door[facing=south,half=lower,hinge=left]", new Cell(1, 0, 0))));
    static final String SHOULD = """
            ```json
            {"kind":"critique","verdict":"revise","problems":[
              {"severity":"should_fix","category":"access","operations":[1],"cell":{"x":1,"y":0,"z":0},
               "problem":"The door is blocked.","fix":"Clear the cell in front of the door."},
              {"severity":"minor","category":"facade","operations":[],"cell":null,
               "problem":"Tiny polish.","fix":"Add a sill."}],
             "keep":["the stone floor"]}
            ```""";

    static Critique parse(String raw) throws PlanException {
        return CritiqueParser.parse(raw, PLAN, KEY);
    }

    @Test
    void parsesAWellFormedCritique() throws Exception {
        Critique c = parse(SHOULD);
        assertEquals(KEY, c.planKey());
        assertEquals(Critique.Verdict.REVISE, c.verdict());
        assertEquals(2, c.problems().size());
        Critique.Problem first = c.problems().get(0);
        assertEquals(Critique.Severity.SHOULD_FIX, first.severity());
        assertEquals(Critique.Category.ACCESS, first.category());
        assertEquals(List.of(1), first.operations());
        assertEquals(new Cell(1, 0, 0), first.cell());
        assertEquals(List.of("the stone floor"), c.keep());
        assertTrue(c.needsRepair());
        assertEquals(List.of(Critique.Category.ACCESS), c.categories(), "minor problems are not counted");
    }

    @Test
    void structuralErrorsRejectTheWholeCritique() {
        String problem = "{\"severity\":\"should_fix\",\"category\":\"roof\",\"operations\":[],\"cell\":null,"
                + "\"problem\":\"p\",\"fix\":\"f\"}";
        for (String bad : List.of(
                "{\"kind\":\"build\",\"verdict\":\"ok\",\"problems\":[],\"keep\":[]}",
                "{\"kind\":\"critique\",\"verdict\":\"maybe\",\"problems\":[],\"keep\":[]}",
                "{\"kind\":\"critique\",\"verdict\":\"ok\",\"problems\":[" + problem.replace("should_fix", "urgent") + "],\"keep\":[]}",
                "{\"kind\":\"critique\",\"verdict\":\"ok\",\"problems\":[" + problem.replace("roof", "vibes") + "],\"keep\":[]}",
                "{\"kind\":\"critique\",\"verdict\":\"ok\",\"problems\":[" + problem.replace("\"f\"", "\"\"") + "],\"keep\":[]}",
                "{\"kind\":\"critique\",\"verdict\":\"ok\",\"problems\":[" + String.join(",", java.util.Collections.nCopies(7, problem)) + "],\"keep\":[]}",
                "{\"kind\":\"critique\",\"verdict\":\"ok\",\"problems\":[],\"keep\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\"]}",
                "{\"kind\":\"critique\",\"verdict\":\"ok\",\"problems\":{},\"keep\":[]}",
                "{\"kind\":\"critique\",\"verdict\":\"ok\",\"problems\":[" + problem.replace("[]", "[1.5]") + "],\"keep\":[]}",
                "not json at all")) {
            assertThrows(PlanException.class, () -> parse(bad), bad);
        }
    }

    @Test
    void outOfRangeReferencesAreDroppedButTheProblemIsKept() throws Exception {
        Critique c = parse("{\"kind\":\"critique\",\"verdict\":\"revise\",\"problems\":[{\"severity\":\"must_fix\","
                + "\"category\":\"structure\",\"operations\":[-1,0,5,0,99999999999],\"cell\":{\"x\":5000,\"y\":0,\"z\":0},"
                + "\"problem\":\"Floating.\",\"fix\":\"Support it.\"}],\"keep\":[]}");
        assertEquals(List.of(0), c.problems().get(0).operations());
        assertNull(c.problems().get(0).cell());
    }

    @Test
    void textIsCleanedAndCut() throws Exception {
        Critique c = parse("{\"kind\":\"critique\",\"verdict\":\"revise\",\"problems\":[{\"severity\":\"must_fix\","
                + "\"category\":\"request\",\"operations\":[],\"cell\":null,\"problem\":\"Door\\u0007 " + "x".repeat(300)
                + "\",\"fix\":\"§cRed\"}],\"keep\":[\"" + "k".repeat(200) + "\"]}");
        Critique.Problem p = c.problems().get(0);
        assertFalse(p.problem().contains("\u0007"));
        assertEquals(Critique.MAX_PROBLEM, p.problem().codePointCount(0, p.problem().length()));
        assertEquals("Red", p.fix());
        assertEquals(Critique.MAX_KEEP_TEXT, c.keep().get(0).length());
    }

    @Test
    void severitiesDecideNotTheVerdict() throws Exception {
        assertTrue(parse(SHOULD.replace("\"revise\"", "\"ok\"")).needsRepair(), "ok with a should_fix still repairs");
        String minorOnly = "{\"kind\":\"critique\",\"verdict\":\"revise\",\"problems\":[{\"severity\":\"minor\","
                + "\"category\":\"facade\",\"operations\":[],\"cell\":null,\"problem\":\"p\",\"fix\":\"f\"}],\"keep\":[]}";
        assertFalse(parse(minorOnly).needsRepair(), "revise with only minor problems does not");
    }

    @Test
    void keyIsStableAndRoundTripsThroughJson() throws Exception {
        Critique c = parse(SHOULD);
        Critique echoed = CritiqueParser.fromJson(JsonParser.parseString(c.toJson().toString()).getAsJsonObject());
        assertEquals(c, echoed);
        assertEquals(c.key(), echoed.key());
        assertTrue(c.key().matches("[0-9a-f]{64}"));
        assertNotEquals(c.key(), CritiqueParser.parse(SHOULD, PLAN, "b".repeat(64)).key(), "the plan is part of the key");
        assertThrows(PlanException.class, () -> CritiqueParser.parse(SHOULD, PLAN, "not a key"));
    }

    @Test
    void notesRoundTripWithAndWithoutAnOutcome() throws Exception {
        Critique c = parse(SHOULD);
        for (RepairOutcome outcome : new RepairOutcome[] {null, RepairOutcome.REJECTED_ROUTE}) {
            CritiqueNote note = new CritiqueNote(c, outcome);
            assertEquals(note, CritiqueNote.parse(JsonParser.parseString(note.toJson().toString()).getAsJsonObject()));
        }
        assertThrows(IllegalArgumentException.class, () -> CritiqueNote.parse(
                JsonParser.parseString("{\"critique\":{\"kind\":\"critique\"},\"outcome\":null}").getAsJsonObject()));
    }
}
