package dev.justbuild.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.justbuild.config.JustBuildConfig.ModelProfile;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Profile changes sent by the panel's profile editor. Keys never travel back to clients, so an edit names the profile
 * it started from and leaves {@code apiKey} null to keep that profile's saved key ("" clears it). A saved key is only
 * kept while the base URL stays the same, so an edit cannot send it to another host.
 */
public final class ProfileEdits {
    static final int MAX_MODEL = 200;
    static final int MAX_URL = 500;
    static final int MAX_KEY = 1000;

    /** One profile as edited; {@code from} is its name when the editor opened (null for a new one). */
    public record Edit(String from, String name, String provider, String model, String baseUrl, String apiKey,
            String reasoningEffort) {
        /** Without a reasoning effort, so the endpoint's default applies. */
        public Edit(String from, String name, String provider, String model, String baseUrl, String apiKey) {
            this(from, name, provider, model, baseUrl, apiKey, null);
        }
    }

    /** The whole list, in order, and which one is active. */
    public record Request(List<Edit> profiles, String active) {
    }

    private ProfileEdits() {
    }

    /** Validates the edits and returns the new profile list; {@code current} is not changed. */
    public static List<ModelProfile> apply(List<ModelProfile> current, Request request) throws ConfigException {
        List<Edit> edits = request.profiles();
        if (edits.isEmpty()) {
            throw new ConfigException("Keep at least one profile.");
        }
        if (edits.size() > JustBuildConfig.MAX_PROFILES) {
            throw new ConfigException("At most " + JustBuildConfig.MAX_PROFILES + " profiles.");
        }
        List<ModelProfile> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Edit edit : edits) {
            ModelProfile p = resolve(current, edit);
            if (!names.add(p.name.toLowerCase(Locale.ROOT))) {
                throw new ConfigException("Two profiles are called “" + p.name + "”.");
            }
            out.add(p);
        }
        String active = request.active() == null ? "" : request.active().strip();
        if (out.stream().noneMatch(p -> p.name.equalsIgnoreCase(active))) {
            throw new ConfigException("No profile called “" + active + "”.");
        }
        return out;
    }

    /** One edited profile with its key filled in from the saved one when the edit leaves it unchanged. */
    public static ModelProfile resolve(List<ModelProfile> current, Edit edit) throws ConfigException {
        String name = edit.name() == null ? "" : edit.name().strip();
        if (name.isEmpty()) {
            throw new ConfigException("Every profile needs a name.");
        }
        if (name.length() > JustBuildConfig.MAX_NAME) {
            throw new ConfigException("Profile names can be at most " + JustBuildConfig.MAX_NAME + " characters.");
        }
        String provider = edit.provider() == null ? "" : edit.provider().strip().toLowerCase(Locale.ROOT);
        if (!JustBuildConfig.PROVIDERS.contains(provider)) {
            throw new ConfigException("Unknown provider “" + edit.provider() + "” in “" + name + "”.");
        }
        String model = edit.model() == null ? "" : edit.model().strip();
        if (model.length() > MAX_MODEL) {
            throw new ConfigException("“" + name + "”: the model name is too long.");
        }
        if (!provider.equals("openai") && !dev.justbuild.provider.CliModel.valid(model)) {
            throw new ConfigException("“" + name + "”: choose a CLI model identifier without spaces or shell characters.");
        }
        ModelProfile p = new ModelProfile(name, provider, model);
        if (p.isOpenAi()) {
            String url = edit.baseUrl() == null ? "" : edit.baseUrl().strip();
            if (url.length() > MAX_URL || !httpUrl(url)) {
                throw new ConfigException("“" + name + "”: the base URL must start with http:// or https://.");
            }
            p.baseUrl = url;
            if (edit.apiKey() != null) {
                p.apiKey = edit.apiKey().strip();
            } else {
                Optional<ModelProfile> before = current.stream()
                        .filter(c -> edit.from() != null && c.name.equalsIgnoreCase(edit.from().strip()))
                        .findFirst();
                p.apiKey = before.filter(c -> c.isOpenAi() && sameUrl(c.baseUrl, url))
                        .map(c -> c.apiKey == null ? "" : c.apiKey).orElse("");
            }
            if (p.apiKey.length() > MAX_KEY) {
                throw new ConfigException("“" + name + "”: the API key is too long.");
            }
            String effort = edit.reasoningEffort();
            if (effort != null && !effort.isBlank()) {
                p.reasoningEffort = ModelProfile.reasoningEffort(effort);
                if (p.reasoningEffort == null) {
                    throw new ConfigException("“" + name + "”: reasoning must be none, low, medium, or high.");
                }
            }
        }
        return p;
    }

    /** Same endpoint, ignoring surrounding spaces, trailing slashes, and case. */
    public static boolean sameUrl(String a, String b) {
        return a != null && b != null && trimUrl(a).equalsIgnoreCase(trimUrl(b));
    }

    private static String trimUrl(String url) {
        String u = url.strip();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static boolean httpUrl(String text) {
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    // ---- JSON for the serverbound packets ----

    public static String toJson(Request request) {
        JsonObject o = new JsonObject();
        JsonArray list = new JsonArray();
        request.profiles().forEach(e -> list.add(object(e)));
        o.add("profiles", list);
        o.addProperty("active", request.active());
        return o.toString();
    }

    public static String toJson(Edit edit) {
        return object(edit).toString();
    }

    public static Request parse(String json) throws ConfigException {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            List<Edit> edits = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("profiles")) {
                edits.add(edit(e.getAsJsonObject()));
            }
            return new Request(edits, string(o, "active"));
        } catch (RuntimeException e) {
            throw new ConfigException("Could not read the profile changes: " + e.getMessage());
        }
    }

    public static Edit parseEdit(String json) throws ConfigException {
        try {
            return edit(JsonParser.parseString(json).getAsJsonObject());
        } catch (RuntimeException e) {
            throw new ConfigException("Could not read the profile: " + e.getMessage());
        }
    }

    private static JsonObject object(Edit e) {
        JsonObject o = new JsonObject();
        o.addProperty("from", e.from());
        o.addProperty("name", e.name());
        o.addProperty("provider", e.provider());
        o.addProperty("model", e.model());
        o.addProperty("baseUrl", e.baseUrl());
        o.addProperty("apiKey", e.apiKey());
        o.addProperty("reasoningEffort", e.reasoningEffort());
        return o;
    }

    private static Edit edit(JsonObject o) {
        return new Edit(string(o, "from"), string(o, "name"), string(o, "provider"), string(o, "model"),
                string(o, "baseUrl"), string(o, "apiKey"), string(o, "reasoningEffort"));
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
