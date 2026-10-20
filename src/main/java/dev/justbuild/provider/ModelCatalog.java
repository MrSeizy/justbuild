package dev.justbuild.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/** Model lists for the profile editor's picker and /justbuild models. */
public final class ModelCatalog {
    /**
     * The Claude CLI has no command that lists models. These are the aliases its --help names (each means the latest
     * model of that tier) followed by the current full model ids.
     */
    public static final List<String> CLAUDE = List.of("fable", "opus", "sonnet", "claude-fable-5-1",
            "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5");

    private ModelCatalog() {
    }

    /** Output of `codex debug models`: the models Codex shows in its picker, in its order. */
    static List<String> codex(String json) throws ProviderException {
        record Entry(String slug, int priority) {
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonElement e : array(json, "models")) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject m = e.getAsJsonObject();
            String slug = string(m, "slug");
            if (slug == null || !"list".equals(string(m, "visibility"))) {
                continue;
            }
            JsonElement priority = m.get("priority");
            boolean numeric = priority != null && priority.isJsonPrimitive() && priority.getAsJsonPrimitive().isNumber();
            entries.add(new Entry(slug, numeric ? priority.getAsInt() : Integer.MAX_VALUE));
        }
        if (entries.isEmpty()) {
            throw new ProviderException("Codex listed no models.");
        }
        return entries.stream().sorted(Comparator.comparingInt(Entry::priority)).map(Entry::slug).toList();
    }

    /** Body of GET {baseUrl}/models: every id, sorted. */
    static List<String> openAi(String json) throws ProviderException {
        TreeSet<String> ids = new TreeSet<>();
        for (JsonElement e : array(json, "data")) {
            if (e.isJsonObject()) {
                String id = string(e.getAsJsonObject(), "id");
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty()) {
            throw new ProviderException("The endpoint listed no models.");
        }
        return List.copyOf(ids);
    }

    private static Iterable<JsonElement> array(String json, String key) throws ProviderException {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (root.isJsonObject() && root.getAsJsonObject().get(key) instanceof com.google.gson.JsonArray a) {
                return a;
            }
        } catch (RuntimeException e) {
            // reported below
        }
        throw new ProviderException("Unexpected model list: " + OpenAiMessages.abbreviate(json));
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
