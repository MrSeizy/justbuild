package dev.justbuild.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.justbuild.plan.PlanParser;

/**
 * Reads `codex exec --json` events line by line: reasoning summaries become headlines (and the THINKING phase), the
 * final message the WRITING phase with its operation count, and the turn's usage the token count.
 */
final class CodexStream {
    static final int MAX_NOTE = 60;

    private final ProgressListener progress;
    private volatile String failure;

    CodexStream(ProgressListener progress) {
        this.progress = progress;
    }

    void accept(String line) {
        JsonObject event;
        try {
            JsonElement element = JsonParser.parseString(line);
            if (!element.isJsonObject()) {
                return;
            }
            event = element.getAsJsonObject();
        } catch (JsonParseException e) {
            return;
        }
        switch (text(event, "type")) {
            case "item.completed" -> {
                if (!(event.get("item") instanceof JsonObject item)) {
                    return;
                }
                switch (text(item, "type")) {
                    case "reasoning" -> {
                        progress.phase(ProgressListener.Phase.THINKING);
                        String headline = headline(text(item, "text"));
                        if (!headline.isEmpty()) {
                            progress.note(headline);
                        }
                    }
                    case "agent_message" -> {
                        progress.phase(ProgressListener.Phase.WRITING);
                        int ops = ProgressListener.countOps(text(item, "text"), 0);
                        if (ops > 0) {
                            progress.operations(ops);
                        }
                    }
                    default -> {
                    }
                }
            }
            case "turn.completed" -> {
                if (event.get("usage") instanceof JsonObject usage) {
                    progress.tokens(number(usage, "input_tokens"), number(usage, "output_tokens"));
                }
            }
            case "turn.failed", "error" -> {
                JsonElement error = event.get("error");
                String message = error instanceof JsonObject o ? text(o, "message") : text(event, "message");
                if (!message.isEmpty()) {
                    failure = message;
                }
            }
            default -> {
            }
        }
    }

    /** Why the turn failed, if Codex said so; null otherwise. */
    String failure() {
        return failure;
    }

    /** "**Choosing materials**\n\n…" gives "Choosing materials"; otherwise the start of the first line. */
    static String headline(String reasoning) {
        String text = reasoning.strip();
        if (text.startsWith("**")) {
            int end = text.indexOf("**", 2);
            if (end > 2) {
                return PlanParser.clean(text.substring(2, end), MAX_NOTE);
            }
        }
        int newline = text.indexOf('\n');
        String first = newline < 0 ? text : text.substring(0, newline);
        String cleaned = PlanParser.clean(first, MAX_NOTE);
        return first.strip().length() > MAX_NOTE ? cleaned + "…" : cleaned;
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static long number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : 0;
    }
}
