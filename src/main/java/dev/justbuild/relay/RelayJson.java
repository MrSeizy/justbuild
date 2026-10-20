package dev.justbuild.relay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.justbuild.prompt.Guidance;

/** JSON inside the relay packets. The request carries no instructions: the player's game builds the system prompt. */
public final class RelayJson {
    /** Bumped whenever the relay packets change; the server only relays to games with the same number. */
    public static final int PROTOCOL = 20;
    public static final int MAX_LIMIT = 1_000_000;
    public static final int MIN_TIMEOUT = 10;
    public static final int MAX_TIMEOUT = 900;
    public static final int MAX_LABEL = 80;

    public static final class RelayException extends Exception {
        public RelayException(String message) {
            super(message);
        }
    }

    /**
     * What the server asks the player's AI: the request as data (the game makes the text itself after checking it)
     * plus the numbers the system prompt states. The relay waits for progress using an inactivity deadline;
     * timeoutSeconds also supplies the CLI idle timeout or HTTP request/body timeout in the player's game.
     */
    public record Request(PromptSpec spec, String detail, int maxOperations, int maxBlocks, int maxSize,
            int timeoutSeconds) {
    }

    /** What the player's game reports on join and when it changes: whether it can plan builds, and with what. */
    public record Hello(int protocol, boolean ready, String label) {
    }

    private RelayJson() {
    }

    public static String toJson(Request r) {
        JsonObject o = new JsonObject();
        o.add("spec", r.spec().toJson());
        o.addProperty("detail", r.detail());
        o.addProperty("maxOperations", r.maxOperations());
        o.addProperty("maxBlocks", r.maxBlocks());
        o.addProperty("maxSize", r.maxSize());
        o.addProperty("timeoutSeconds", r.timeoutSeconds());
        return o.toString();
    }

    public static Request parseRequest(String json) throws RelayException {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (!(o.get("spec") instanceof JsonObject specJson)) {
                throw new RelayException("The server's request has no spec.");
            }
            PromptSpec spec;
            try {
                spec = PromptSpec.parse(specJson);
            } catch (PromptSpec.SpecException e) {
                throw new RelayException(e.getMessage());
            }
            String detailText = string(o, "detail");
            String detail = Guidance.DetailLevel.parse(detailText == null ? "" : detailText)
                    .orElse(Guidance.DetailLevel.DETAILED).id();
            return new Request(spec, detail, clamp(o, "maxOperations", 1, MAX_LIMIT),
                    clamp(o, "maxBlocks", 1, MAX_LIMIT), clamp(o, "maxSize", 1, MAX_LIMIT),
                    clamp(o, "timeoutSeconds", MIN_TIMEOUT, MAX_TIMEOUT));
        } catch (RuntimeException e) {
            throw new RelayException("Could not read the server's request: " + e.getMessage());
        }
    }

    public static String toJson(Hello h) {
        JsonObject o = new JsonObject();
        o.addProperty("protocol", h.protocol());
        o.addProperty("ready", h.ready());
        o.addProperty("label", h.label());
        return o.toString();
    }

    /** Never throws: anything unreadable is a game that is not ready. */
    public static Hello parseHello(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String label = string(o, "label");
            label = label == null ? "" : label.strip();
            return new Hello(o.has("protocol") ? o.get("protocol").getAsInt() : 0,
                    o.has("ready") && o.get("ready").getAsBoolean(),
                    label.length() > MAX_LABEL ? label.substring(0, MAX_LABEL) : label);
        } catch (RuntimeException e) {
            return new Hello(0, false, "");
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static int clamp(JsonObject o, String key, int min, int max) {
        JsonElement e = o.get(key);
        long value = e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : min;
        return (int) Math.max(min, Math.min(max, value));
    }
}
