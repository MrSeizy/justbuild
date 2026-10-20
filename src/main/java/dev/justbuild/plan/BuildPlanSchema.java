package dev.justbuild.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON Schema every backend must answer with. Each operation type is its own anyOf variant listing only the fields
 * it uses; all fields are required (nullable where optional) so Claude --json-schema, Codex --output-schema, and
 * OpenAI strict json_schema all accept it.
 */
public final class BuildPlanSchema {
    /** Fields of each operation type, after "op" and before "repeat" and "mirror". PlanWriter writes the same set. */
    static final Map<OpType, List<String>> FIELDS = fields();

    public static final String JSON = schema().toString();

    private BuildPlanSchema() {
    }

    private static Map<OpType, List<String>> fields() {
        Map<OpType, List<String>> f = new LinkedHashMap<>();
        f.put(OpType.SET, List.of("block", "from"));
        f.put(OpType.FILL, List.of("block", "from", "to", "hollow", "mix"));
        f.put(OpType.LINE, List.of("block", "from", "to"));
        f.put(OpType.SPHERE, List.of("block", "from", "radius", "style", "hollow", "mix"));
        f.put(OpType.ELLIPSOID, List.of("block", "from", "radii", "style", "hollow", "mix"));
        f.put(OpType.CYLINDER, List.of("block", "from", "radius", "height", "hollow", "mix"));
        f.put(OpType.ROOF, List.of("block", "trim", "edge", "from", "to", "style", "axis", "pitch", "rim", "eaves", "flare",
                "mix", "beam", "finial", "height"));
        f.put(OpType.STAIRS, List.of("block", "trim", "from", "to", "direction", "height"));
        f.put(OpType.ARCH, List.of("block", "from", "radius", "height", "axis", "hollow", "style"));
        f.put(OpType.VOXELS, List.of("palette", "from", "layers"));
        f.put(OpType.REPLACE, List.of("block", "target", "from", "to"));
        f.put(OpType.FOUNDATION, List.of("block", "from", "to"));
        f.put(OpType.PORTAL, List.of("style", "from", "to", "hollow"));
        return f;
    }

    private static JsonObject schema() {
        JsonArray variants = new JsonArray();
        FIELDS.forEach((type, names) -> {
            JsonObject properties = new JsonObject();
            properties.add("op", enumOf("string", type.id()));
            for (String name : names) {
                properties.add(name, field(type, name));
            }
            properties.add("repeat", nullableObject(
                    property("count", type("integer")),
                    property("step", intArray())));
            properties.add("mirror", nullableObject(
                    property("axis", enumOf("string", "x", "z")),
                    property("at", type("number"))));
            variants.add(object(properties));
        });
        JsonObject items = new JsonObject();
        items.add("anyOf", variants);
        JsonObject operations = new JsonObject();
        operations.add("type", strings("array", "null"));
        operations.add("items", items);

        JsonObject properties = new JsonObject();
        properties.add("kind", enumOf("string", "build", "question", "decline", "guides", "inspect", "blocks"));
        properties.add("inspection", nullableObject(property("from", intArray()), property("to", intArray()),
                property("mode", nullableEnum("sample", "exact", "surface")), property("target", nullableEnum("source", "candidate"))));
        properties.add("blockQuery", nullableObject(property("query", type("string")), property("offset", type("integer"))));
        properties.add("question", nullable("string"));
        JsonObject options = new JsonObject();
        options.add("type", strings("array", "null"));
        options.add("items", type("string"));
        properties.add("options", options);
        properties.add("title", nullable("string"));
        properties.add("summary", nullable("string"));
        properties.add("operations", operations);
        JsonObject guides = new JsonObject();
        guides.add("type", strings("array", "null"));
        guides.add("items", type("string"));
        properties.add("guides", guides);
        JsonObject weights = type("array");
        JsonObject entry = new JsonObject(); entry.add("block", type("string")); entry.add("weight", type("integer"));
        weights.add("items", object(entry));
        properties.add("texture", nullableObject(property("seed", type("integer")), property("weights", weights)));
        return object(properties);
    }

    private static JsonObject field(OpType type, String name) {
        return switch (name) {
            case "block", "target" -> type("string");
            case "trim", "edge", "beam" -> nullable("string");
            case "finial", "mix" -> {
                JsonObject a = new JsonObject();
                a.add("type", strings("array", "null"));
                a.add("items", type("string"));
                yield a;
            }
            case "pitch" -> nullable("integer");
            case "rim", "eaves", "flare" -> nullable("boolean");
            case "from" -> intArray();
            case "to" -> {
                // An end portal has only a center.
                if (type != OpType.PORTAL) {
                    yield intArray();
                }
                JsonObject a = new JsonObject();
                a.add("type", strings("array", "null"));
                a.add("items", type("integer"));
                yield a;
            }
            case "radius" -> type("number");
            case "height" -> type == OpType.ROOF ? nullable("integer") : type("integer");
            case "hollow" -> type("boolean");
            case "style" -> type == OpType.ROOF ? enumOf("string", "gable", "hip", "flat", "gambrel", "spire")
                    : type == OpType.ARCH ? enumOf("string", "round", "pointed")
                    : type == OpType.PORTAL ? enumOf("string", "nether", "end")
                    : enumOf("string", "full", "dome", "bowl");
            case "axis" -> type == OpType.ROOF ? nullableEnum("x", "z") : enumOf("string", "x", "z");
            case "direction" -> enumOf("string", "north", "south", "east", "west");
            case "radii" -> {
                JsonObject a = type("array");
                a.add("items", type("number"));
                yield a;
            }
            case "palette" -> {
                JsonObject entry = new JsonObject();
                entry.add("char", type("string"));
                entry.add("block", type("string"));
                JsonObject a = type("array");
                a.add("items", object(entry));
                yield a;
            }
            case "layers" -> {
                JsonObject rows = type("array");
                rows.add("items", type("string"));
                JsonObject a = type("array");
                a.add("items", rows);
                yield a;
            }
            default -> throw new IllegalArgumentException("unknown field " + name);
        };
    }

    private static JsonObject object(JsonObject properties) {
        JsonObject o = type("object");
        o.addProperty("additionalProperties", false);
        JsonArray required = new JsonArray();
        properties.keySet().forEach(required::add);
        o.add("required", required);
        o.add("properties", properties);
        return o;
    }

    private static JsonObject nullableObject(JsonObject... props) {
        JsonObject properties = new JsonObject();
        for (JsonObject p : props) {
            p.entrySet().forEach(e -> properties.add(e.getKey(), e.getValue()));
        }
        JsonObject o = object(properties);
        o.add("type", strings("object", "null"));
        return o;
    }

    private static JsonObject property(String name, JsonObject schema) {
        JsonObject p = new JsonObject();
        p.add(name, schema);
        return p;
    }

    private static JsonObject type(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return o;
    }

    private static JsonObject nullable(String type) {
        JsonObject o = new JsonObject();
        o.add("type", strings(type, "null"));
        return o;
    }

    private static JsonObject intArray() {
        JsonObject a = type("array");
        a.add("items", type("integer"));
        return a;
    }

    private static JsonObject enumOf(String type, String... values) {
        JsonObject o = type(type);
        o.add("enum", strings(values));
        return o;
    }

    private static JsonObject nullableEnum(String... values) {
        JsonObject o = nullable("string");
        JsonArray e = strings(values);
        e.add(com.google.gson.JsonNull.INSTANCE);
        o.add("enum", e);
        return o;
    }

    private static JsonArray strings(String... values) {
        JsonArray a = new JsonArray();
        for (String v : values) {
            a.add(v);
        }
        return a;
    }
}
