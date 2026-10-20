package dev.justbuild.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Writes a plan in the schema's compact form (only the fields each operation uses). Round-trips via PlanParser. */
public final class PlanWriter {
    private PlanWriter() {
    }

    public static String write(BuildPlan plan) {
        return toJson(plan).toString();
    }

    public static JsonObject toJson(BuildPlan plan) {
        JsonObject root = new JsonObject();
        root.addProperty("kind", "build");
        root.add("question", JsonNull.INSTANCE);
        root.add("options", JsonNull.INSTANCE);
        root.addProperty("title", plan.title());
        root.addProperty("summary", plan.summary());
        JsonArray ops = new JsonArray();
        plan.operations().forEach(op -> ops.add(operation(op)));
        root.add("operations", ops);
        root.add("guides", JsonNull.INSTANCE);
        root.add("inspection", JsonNull.INSTANCE);
        root.add("blockQuery", JsonNull.INSTANCE);
        root.add("texture", plan.texture().equals(TextureSettings.DEFAULT) ? JsonNull.INSTANCE : plan.texture().toJson());
        return root;
    }

    private static com.google.gson.JsonElement strings(List<String> values) {
        if (values == null) {
            return JsonNull.INSTANCE;
        }
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }

    static JsonObject operation(Operation op) {
        JsonObject o = new JsonObject();
        o.addProperty("op", op.op().id());
        for (String field : BuildPlanSchema.FIELDS.get(op.op())) {
            switch (field) {
                case "block" -> o.addProperty("block", op.block());
                case "trim" -> o.addProperty("trim", op.trim());
                case "edge" -> o.addProperty("edge", op.edge());
                case "pitch" -> o.addProperty("pitch", op.pitch());
                case "rim" -> o.addProperty("rim", op.rim());
                case "eaves" -> o.addProperty("eaves", op.eaves());
                case "flare" -> o.addProperty("flare", op.flare());
                case "mix" -> o.add("mix", strings(op.mix()));
                case "beam" -> o.addProperty("beam", op.beam());
                case "finial" -> o.add("finial", strings(op.finial()));
                case "target" -> o.addProperty("target", op.target());
                case "from" -> o.add("from", cell(op.from()));
                case "to" -> o.add("to", op.to() == null ? JsonNull.INSTANCE : cell(op.to()));
                case "radius" -> o.addProperty("radius", op.radius());
                case "height" -> o.addProperty("height", op.height() == null && op.op() != OpType.ROOF ? Integer.valueOf(0)
                        : op.height());
                case "hollow" -> o.addProperty("hollow", op.hollow());
                case "style" -> o.addProperty("style", op.style() != null ? op.style()
                        : op.op() == OpType.ROOF ? "gable" : op.op() == OpType.ARCH ? "round"
                        : op.op() == OpType.PORTAL ? "nether" : "full");
                case "axis" -> o.addProperty("axis", op.axis() != null || op.op() == OpType.ROOF ? op.axis() : "x");
                case "direction" -> o.addProperty("direction", op.direction());
                case "radii" -> {
                    JsonArray a = new JsonArray();
                    a.add(op.radii().x());
                    a.add(op.radii().y());
                    a.add(op.radii().z());
                    o.add("radii", a);
                }
                case "palette" -> {
                    JsonArray a = new JsonArray();
                    for (Map.Entry<Character, String> e : new TreeMap<>(op.palette()).entrySet()) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("char", String.valueOf(e.getKey()));
                        entry.addProperty("block", e.getValue());
                        a.add(entry);
                    }
                    o.add("palette", a);
                }
                case "layers" -> {
                    JsonArray a = new JsonArray();
                    for (List<String> layer : op.layers()) {
                        JsonArray rows = new JsonArray();
                        layer.forEach(rows::add);
                        a.add(rows);
                    }
                    o.add("layers", a);
                }
                default -> throw new IllegalStateException("unknown field " + field);
            }
        }
        if (op.repeat() == null) {
            o.add("repeat", JsonNull.INSTANCE);
        } else {
            JsonObject r = new JsonObject();
            r.addProperty("count", op.repeat().count());
            r.add("step", cell(op.repeat().step()));
            o.add("repeat", r);
        }
        if (op.mirror() == null) {
            o.add("mirror", JsonNull.INSTANCE);
        } else {
            JsonObject m = new JsonObject();
            m.addProperty("axis", op.mirror().axis());
            m.addProperty("at", op.mirror().at());
            o.add("mirror", m);
        }
        return o;
    }

    private static JsonArray cell(Cell c) {
        JsonArray a = new JsonArray();
        a.add(c.x());
        a.add(c.y());
        a.add(c.z());
        return a;
    }
}
