package dev.justbuild.plan;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.justbuild.world.BlockResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Material substitutions affect every block-valued field, including palettes and roof details. */
public final class PlanMaterials {
    private static final Set<String> BLOCK_FIELDS = Set.of("block", "trim", "target", "edge", "beam");

    private PlanMaterials() { }

    public static Map<String, String> parse(String text) throws PlanException {
        Map<String, String> pairs = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return pairs;
        for (String pair : text.split(",")) {
            String[] parts = pair.strip().split("=", -1);
            if (parts.length != 2 || parts[0].contains("[") || parts[1].contains("[")) {
                throw new PlanException("Use block=block substitutions, separated by commas (without block properties).");
            }
            String from = qualify(parts[0].strip()), to = qualify(parts[1].strip());
            if (BlockResolver.parse(from).isEmpty() || BlockResolver.parse(to).isEmpty()) {
                throw new PlanException("Unknown substitution: " + pair);
            }
            if (pairs.putIfAbsent(from, to) != null) throw new PlanException("A material is substituted more than once.");
            if (pairs.size() > 16) throw new PlanException("Use at most 16 material substitutions.");
        }
        return Map.copyOf(pairs);
    }

    public static BuildPlan apply(BuildPlan plan, String title, Map<String, String> pairs) throws PlanException {
        JsonObject json = PlanWriter.toJson(plan);
        json.addProperty("title", title);
        Map<String, Integer> weights = new LinkedHashMap<>();
        plan.texture().weights().forEach((id, weight) -> weights.put(pairs.getOrDefault(id, id), weight));
        json.add("texture", new TextureSettings(plan.texture().seed(), weights).toJson());
        for (JsonElement element : json.getAsJsonArray("operations")) {
            JsonObject op = element.getAsJsonObject();
            for (String key : BLOCK_FIELDS) if (op.has(key) && !op.get(key).isJsonNull())
                op.addProperty(key, replace(op.get(key).getAsString(), pairs));
            if (op.has("palette")) {
                for (JsonElement entry : op.getAsJsonArray("palette")) {
                    JsonObject value = entry.getAsJsonObject();
                    value.addProperty("block", replace(value.get("block").getAsString(), pairs));
                }
            }
            for (String key : new String[] {"mix", "finial"}) if (op.has(key) && !op.get(key).isJsonNull()) {
                var values = op.getAsJsonArray(key);
                for (int i = 0; i < values.size(); i++) values.set(i, new com.google.gson.JsonPrimitive(replace(values.get(i).getAsString(), pairs)));
            }
        }
        return ((PlanResponse.Build) PlanParser.parse(json.toString())).plan();
    }

    private static String replace(String state, Map<String, String> pairs) throws PlanException {
        int properties = state.indexOf('[');
        String block = qualify(properties < 0 ? state : state.substring(0, properties));
        String replacement = pairs.get(block);
        if (replacement == null) return state;
        String result = replacement + (properties < 0 ? "" : state.substring(properties));
        if (BlockResolver.parse(result).isEmpty()) throw new PlanException("The replacement cannot preserve these block properties: " + result);
        return result;
    }

    private static String qualify(String block) { return block.contains(":") ? block : "minecraft:" + block; }
}
