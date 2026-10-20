package dev.justbuild.plan;

import com.google.gson.*;
import java.util.*;

/** Reproducible material choices. Defaults preserve the original coordinate hash and mixing ratios. */
public record TextureSettings(int seed, Map<String, Integer> weights) {
    public static final TextureSettings DEFAULT = new TextureSettings(0, Map.of());
    public TextureSettings {
        weights = Map.copyOf(weights);
        if (weights.size() > 64) throw new IllegalArgumentException("At most 64 material weights are allowed.");
        weights.forEach((id, weight) -> {
            if (!id.matches("[a-z0-9_.-]{1,40}:[a-z0-9_./-]{1,80}") || weight < 0 || weight > 100)
                throw new IllegalArgumentException("Material weights need plain block IDs and values from 0 to 100.");
        });
    }
    public int pick(int a, int b, String main, List<String> mix) {
        if (mix == null || mix.isEmpty()) return 0;
        int total = weight(main, 2);
        for (String state : mix) total += weight(state, 1);
        if (total == 0) return mix.size();
        int hash = a * 73_856_093 ^ b * 19_349_663 ^ seed * 83_492_791;
        hash ^= hash >>> 15;
        int selected = Math.floorMod(hash * 0x2c1b3c6d, total);
        for (int i = 0; i < mix.size(); i++) {
            selected -= weight(mix.get(i), 1);
            if (selected < 0) return i;
        }
        return mix.size();
    }
    private int weight(String state, int fallback) {
        String id = BlockSpecs.id(state);
        if (!id.contains(":")) id = "minecraft:" + id;
        return weights.getOrDefault(id, fallback);
    }
    public JsonObject toJson() {
        JsonObject out = new JsonObject(); out.addProperty("seed", seed);
        JsonArray entries = new JsonArray();
        new TreeMap<>(weights).forEach((id, value) -> {
            JsonObject e = new JsonObject(); e.addProperty("block", id); e.addProperty("weight", value); entries.add(e);
        });
        out.add("weights", entries); return out;
    }
    public static TextureSettings parse(JsonElement value) {
        if (value == null || value.isJsonNull()) return DEFAULT;
        JsonObject object = value.getAsJsonObject();
        Map<String, Integer> weights = new LinkedHashMap<>();
        JsonArray entries = object.getAsJsonArray("weights");
        if (entries.size() > 64) throw new IllegalArgumentException("Too many material weights.");
        for (JsonElement element : entries) {
            JsonObject e = element.getAsJsonObject();
            if (weights.putIfAbsent(e.get("block").getAsString(), integer(e.get("weight"))) != null)
                throw new IllegalArgumentException("Duplicate material weight.");
        }
        return new TextureSettings(integer(object.get("seed")), weights);
    }
    private static int integer(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected integer.");
        return value.getAsBigDecimal().intValueExact();
    }
}
