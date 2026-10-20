package dev.justbuild.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** A bounded, read-only box in the current build's coordinate frame. No dimension or executable text. */
public record InspectionRequest(Cell from, Cell to, Mode mode, Target target) {
    public enum Mode { SAMPLE, EXACT, SURFACE }
    public enum Target { SOURCE, CANDIDATE }
    public InspectionRequest(Cell from, Cell to) { this(from, to, Mode.SAMPLE, Target.SOURCE); }
    public static final int MAX_OFFSET = 128;
    public static final int MAX_SPAN = 128;
    public static final int MAX_ROUNDS = 4;

    public InspectionRequest {
        mode = mode == null ? Mode.SAMPLE : mode;
        target = target == null ? Target.SOURCE : target;
        if (from == null || to == null) {
            throw new IllegalArgumentException("an inspection needs two corners");
        }
        Cell a = from;
        from = new Cell(Math.min(a.x(), to.x()), Math.min(a.y(), to.y()), Math.min(a.z(), to.z()));
        to = new Cell(Math.max(a.x(), to.x()), Math.max(a.y(), to.y()), Math.max(a.z(), to.z()));
        for (int n : new int[] {from.x(), from.y(), from.z(), to.x(), to.y(), to.z()}) {
            if (Math.abs((long) n) > MAX_OFFSET) {
                throw new IllegalArgumentException("inspection coordinates must be within ±" + MAX_OFFSET);
            }
        }
        if (to.x() - from.x() >= MAX_SPAN || to.y() - from.y() >= MAX_SPAN || to.z() - from.z() >= MAX_SPAN) {
            throw new IllegalArgumentException("an inspection can span at most " + MAX_SPAN + " blocks per axis");
        }
    }

    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.add("from", json(from));
        out.add("to", json(to));
        out.addProperty("mode", mode.name().toLowerCase(java.util.Locale.ROOT));
        out.addProperty("target", target.name().toLowerCase(java.util.Locale.ROOT));
        return out;
    }

    public static InspectionRequest parse(JsonObject o) {
        return new InspectionRequest(cell(o.get("from")), cell(o.get("to")),
                o.has("mode") && !o.get("mode").isJsonNull() ? Mode.valueOf(o.get("mode").getAsString().toUpperCase(java.util.Locale.ROOT)) : Mode.SAMPLE,
                o.has("target") && !o.get("target").isJsonNull() ? Target.valueOf(o.get("target").getAsString().toUpperCase(java.util.Locale.ROOT)) : Target.SOURCE);
    }

    private static JsonArray json(Cell p) {
        JsonArray a = new JsonArray();
        a.add(p.x());
        a.add(p.y());
        a.add(p.z());
        return a;
    }

    private static Cell cell(JsonElement value) {
        if (!(value instanceof JsonArray a) || a.size() != 3) {
            throw new IllegalArgumentException("inspection corners must each contain three integers");
        }
        return new Cell(integer(a.get(0)), integer(a.get(1)), integer(a.get(2)));
    }

    private static int integer(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("inspection coordinates must be integers");
        }
        return e.getAsBigDecimal().intValueExact();
    }
}
