package dev.justbuild.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads a reviewer's answer. Structural errors (wrong shapes or types, unknown choices, too many entries) reject the
 * whole critique; operation indices outside the plan and cells that are unusable or beyond the coordinate bounds are
 * dropped, keeping the problem. Text is cleaned with {@link PlanParser#visible}.
 */
public final class CritiqueParser {
    private CritiqueParser() {
    }

    /** The reviewer's raw answer about {@code plan}, whose key is {@code planKey}. */
    public static Critique parse(String raw, BuildPlan plan, String planKey) throws PlanException {
        JsonObject root;
        try {
            JsonElement element = JsonParser.parseString(JsonText.extractObject(raw));
            if (!element.isJsonObject()) throw new PlanException("the critique is not a JSON object");
            root = element.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new PlanException("the critique is not valid JSON");
        }
        return read(root, planKey, plan.operations().size());
    }

    /**
     * A critique as relayed between games: already normalized, so it carries its own plan key. Operation indices are
     * not checked against any plan.
     */
    public static Critique fromJson(JsonObject o) throws PlanException {
        return read(o, string(o.get("planKey")), Integer.MAX_VALUE);
    }

    private static Critique read(JsonObject root, String planKey, int operationCount) throws PlanException {
        if (!"critique".equals(string(root.get("kind")))) throw new PlanException("the response is not a critique");
        Critique.Verdict verdict = choice(root.get("verdict"), Critique.Verdict.class, "verdict");
        JsonArray list = array(root.get("problems"), "problems");
        if (list.size() > Critique.MAX_PROBLEMS) throw new PlanException("the critique lists too many problems");
        List<Critique.Problem> problems = new ArrayList<>();
        for (JsonElement element : list) {
            if (!element.isJsonObject()) throw new PlanException("a critique problem is not an object");
            JsonObject p = element.getAsJsonObject();
            JsonElement refsElement = p.get("operations");
            JsonArray refs = refsElement == null || refsElement.isJsonNull()
                    ? new JsonArray() : array(refsElement, "operations");
            if (refs.size() > Critique.MAX_OPERATIONS) throw new PlanException("a critique problem names too many operations");
            List<Integer> indices = new ArrayList<>();
            for (JsonElement ref : refs) {
                Long index = integer(ref);
                if (index == null) throw new PlanException("a critique operation is not an integer");
                if (index >= 0 && index < operationCount && !indices.contains((int) (long) index)) {
                    indices.add((int) (long) index);
                }
            }
            String problem = PlanParser.visible(string(p.get("problem")), Critique.MAX_PROBLEM_TEXT);
            String fix = PlanParser.visible(string(p.get("fix")), Critique.MAX_FIX_TEXT);
            if (problem.isEmpty() || fix.isEmpty()) throw new PlanException("a critique problem needs a problem and a fix");
            problems.add(new Critique.Problem(choice(p.get("severity"), Critique.Severity.class, "severity"),
                    choice(p.get("category"), Critique.Category.class, "category"), indices, cell(p.get("cell")), problem, fix));
        }
        JsonArray keepList = array(root.get("keep"), "keep");
        if (keepList.size() > Critique.MAX_KEEP) throw new PlanException("the critique keeps too many items");
        List<String> keep = new ArrayList<>();
        for (JsonElement element : keepList) {
            String text = string(element);
            if (text == null) throw new PlanException("a kept item is not text");
            String item = PlanParser.visible(text, Critique.MAX_KEEP_TEXT);
            if (!item.isEmpty()) keep.add(item);
        }
        try {
            return new Critique(planKey, verdict, problems, keep);
        } catch (IllegalArgumentException e) {
            throw new PlanException("the critique is not about a known plan");
        }
    }

    /** A cell is optional, so anything that is not a usable cell (an object {x,y,z} or an array [x,y,z]) is dropped. */
    private static Cell cell(JsonElement e) {
        Long x, y, z;
        if (e != null && e.isJsonObject()) {
            JsonObject c = e.getAsJsonObject();
            x = integer(c.get("x"));
            y = integer(c.get("y"));
            z = integer(c.get("z"));
        } else if (e != null && e.isJsonArray() && e.getAsJsonArray().size() == 3) {
            JsonArray c = e.getAsJsonArray();
            x = integer(c.get(0));
            y = integer(c.get(1));
            z = integer(c.get(2));
        } else {
            return null;
        }
        if (x == null || y == null || z == null || outside(x) || outside(y) || outside(z)) return null;
        return new Cell((int) (long) x, (int) (long) y, (int) (long) z);
    }

    private static boolean outside(long coordinate) {
        return coordinate == Long.MIN_VALUE || Math.abs(coordinate) > Critique.MAX_CELL;
    }

    /**
     * A JSON number as an integer, or null when it is not a number, is fractional, or cannot be read at all. A
     * well-formed integer too large to be any index or coordinate becomes Long.MIN_VALUE, which every range check drops.
     */
    private static Long integer(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        try {
            BigDecimal value = e.getAsJsonPrimitive().getAsBigDecimal();
            if (value.stripTrailingZeros().scale() > 0) return null;
            return value.abs().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0 ? Long.MIN_VALUE : value.longValueExact();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static <E extends Enum<E>> E choice(JsonElement e, Class<E> type, String field) throws PlanException {
        String value = string(e);
        if (value == null) throw new PlanException("the critique has an unknown " + field);
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new PlanException("the critique has an unknown " + field);
        }
    }

    private static JsonArray array(JsonElement e, String field) throws PlanException {
        if (e == null || !e.isJsonArray()) throw new PlanException("the critique's " + field + " is not a list");
        return e.getAsJsonArray();
    }

    /** The text of a JSON string, or null for anything else. */
    private static String string(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }
}
