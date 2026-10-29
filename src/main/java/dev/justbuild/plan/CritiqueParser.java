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
 * Reads a reviewer's answer. Structural errors reject the whole critique; operation indices outside the plan and
 * cells beyond the coordinate bounds are dropped, keeping the problem; text is cleaned like plan summaries.
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

    /** A critique as relayed between games: already normalized, so it carries its own plan key. */
    public static Critique fromJson(JsonObject o) throws PlanException {
        JsonElement key = o.get("planKey");
        return read(o, key == null || !key.isJsonPrimitive() ? null : key.getAsString(), Integer.MAX_VALUE);
    }

    private static Critique read(JsonObject root, String planKey, int operations) throws PlanException {
        if (!"critique".equals(text(root.get("kind")))) throw new PlanException("the response is not a critique");
        Critique.Verdict verdict = choice(root.get("verdict"), Critique.Verdict.class, "verdict");
        JsonArray list = array(root.get("problems"), "problems");
        if (list.size() > Critique.MAX_PROBLEMS) throw new PlanException("the critique lists too many problems");
        List<Critique.Problem> problems = new ArrayList<>();
        for (JsonElement element : list) {
            if (!element.isJsonObject()) throw new PlanException("a critique problem is not an object");
            JsonObject p = element.getAsJsonObject();
            JsonElement refs = p.get("operations");
            JsonArray indices = refs == null || refs.isJsonNull() ? new JsonArray() : array(refs, "operations");
            if (indices.size() > Critique.MAX_OPERATIONS) throw new PlanException("a critique problem names too many operations");
            List<Integer> kept = new ArrayList<>();
            for (JsonElement ref : indices) {
                long index = integer(ref);
                if (index >= 0 && index < operations && !kept.contains((int) index)) kept.add((int) index);
            }
            String problem = PlanParser.visible(text(p.get("problem")), Critique.MAX_PROBLEM);
            String fix = PlanParser.visible(text(p.get("fix")), Critique.MAX_FIX);
            if (problem.isEmpty() || fix.isEmpty()) throw new PlanException("a critique problem needs a problem and a fix");
            problems.add(new Critique.Problem(choice(p.get("severity"), Critique.Severity.class, "severity"),
                    choice(p.get("category"), Critique.Category.class, "category"), kept, cell(p.get("cell")), problem, fix));
        }
        JsonArray keepList = array(root.get("keep"), "keep");
        if (keepList.size() > Critique.MAX_KEEP) throw new PlanException("the critique keeps too many items");
        List<String> keep = new ArrayList<>();
        for (JsonElement element : keepList) {
            if (!element.isJsonPrimitive()) throw new PlanException("a kept item is not text");
            String item = PlanParser.visible(element.getAsString(), Critique.MAX_KEEP_TEXT);
            if (!item.isEmpty()) keep.add(item);
        }
        try {
            return new Critique(planKey, verdict, problems, keep);
        } catch (IllegalArgumentException e) {
            throw new PlanException("the critique is not about a known plan");
        }
    }

    private static Cell cell(JsonElement e) throws PlanException {
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonObject()) throw new PlanException("a critique cell is not an object");
        JsonObject c = e.getAsJsonObject();
        long x = integer(c.get("x")), y = integer(c.get("y")), z = integer(c.get("z"));
        if (outside(x) || outside(y) || outside(z)) return null;
        return new Cell((int) x, (int) y, (int) z);
    }

    private static boolean outside(long coordinate) {
        return coordinate == Long.MIN_VALUE || Math.abs(coordinate) > Critique.MAX_CELL;
    }

    /** An integer, or Long.MIN_VALUE when it is too large to be any index or coordinate; fractions are structural errors. */
    private static long integer(JsonElement e) throws PlanException {
        try {
            BigDecimal value = e.getAsJsonPrimitive().getAsBigDecimal();
            if (value.stripTrailingZeros().scale() > 0) throw new PlanException("a critique number is not an integer");
            return value.abs().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0 ? Long.MIN_VALUE : value.longValueExact();
        } catch (RuntimeException ex) {
            throw new PlanException("a critique number is not an integer");
        }
    }

    private static <E extends Enum<E>> E choice(JsonElement e, Class<E> type, String field) throws PlanException {
        String value = text(e);
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new PlanException("the critique has an unknown " + field);
        }
    }

    private static JsonArray array(JsonElement e, String field) throws PlanException {
        if (e == null || !e.isJsonArray()) throw new PlanException("the critique's " + field + " is not a list");
        return e.getAsJsonArray();
    }

    private static String text(JsonElement e) {
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }
}
