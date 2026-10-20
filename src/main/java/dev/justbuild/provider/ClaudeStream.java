package dev.justbuild.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Reads `claude -p --output-format stream-json --include-partial-messages` line by line for progress and the result. */
final class ClaudeStream {
    private final ProgressListener progress;
    private final StringBuilder planJson = new StringBuilder();
    private int operations;
    private volatile String resultLine;

    ClaudeStream(ProgressListener progress) {
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
        String type = text(event, "type");
        if ("result".equals(type)) {
            resultLine = line;
            if (event.get("usage") instanceof JsonObject usage) {
                progress.tokens(number(usage, "input_tokens") + number(usage, "cache_creation_input_tokens")
                        + number(usage, "cache_read_input_tokens"), number(usage, "output_tokens"));
            }
            return;
        }
        if ("rate_limit_event".equals(type) && event.get("rate_limit_info") instanceof JsonObject info) {
            limitNotice(info, Instant.now()).ifPresent(n -> progress.notice(n[0], n[1]));
            return;
        }
        if (!"stream_event".equals(type) || !event.has("event") || !event.get("event").isJsonObject()) {
            return;
        }
        JsonObject inner = event.getAsJsonObject("event");
        String innerType = text(inner, "type");
        if ("content_block_start".equals(innerType) && inner.has("content_block")) {
            String block = text(inner.getAsJsonObject("content_block"), "type");
            if ("thinking".equals(block)) {
                progress.phase(ProgressListener.Phase.THINKING);
            } else if ("tool_use".equals(block) || "text".equals(block)) {
                progress.phase(ProgressListener.Phase.WRITING);
            }
        } else if ("content_block_delta".equals(innerType) && inner.has("delta")) {
            JsonObject delta = inner.getAsJsonObject("delta");
            if ("input_json_delta".equals(text(delta, "type"))) {
                int from = planJson.length() - 3;
                planJson.append(text(delta, "partial_json"));
                int found = ProgressListener.countOps(planJson, from);
                if (found > 0) {
                    operations += found;
                    progress.operations(operations);
                }
            }
        }
    }

    /**
     * A warning when the subscription is near or at a usage limit: the busiest window at 75% or more, or whatever
     * the CLI flags (allowed_warning, rejected). {key, message}; the key changes every 10%, so each level is said once.
     */
    static Optional<String[]> limitNotice(JsonObject info, Instant now) {
        String status = text(info, "status");
        String window = text(info, "rateLimitType");
        double utilization = decimal(info, "utilization");
        long resetsAt = (long) decimal(info, "resetsAt");
        if (info.get("unifiedWindows") instanceof JsonObject windows && !windows.isEmpty()) {
            utilization = -1;
            for (Map.Entry<String, JsonElement> w : windows.entrySet()) {
                if (w.getValue() instanceof JsonObject o && decimal(o, "utilization") > utilization) {
                    window = w.getKey();
                    utilization = decimal(o, "utilization");
                    resetsAt = (long) decimal(o, "resetsAt");
                }
            }
        }
        String name = switch (window) {
            case "five_hour" -> "5-hour";
            case "seven_day" -> "weekly";
            case "seven_day_opus" -> "weekly Opus";
            case "seven_day_sonnet" -> "weekly Sonnet";
            default -> window.replace('_', ' ');
        };
        String resets = resetsAt > 0 ? "; it resets in " + until(Duration.ofSeconds(Math.max(0, resetsAt - now.getEpochSecond())))
                : "";
        if ("rejected".equals(status) || utilization >= 1.0) {
            return Optional.of(new String[] {"claude-" + window + "-used-" + resetsAt,
                    "Your Claude plan's " + name + " limit is used up" + resets + "."});
        }
        if (utilization >= 0.75 || "allowed_warning".equals(status)) {
            int percent = (int) Math.round(utilization * 100);
            return Optional.of(new String[] {"claude-" + window + "-" + (percent / 10 * 10),
                    "Your Claude plan has used " + percent + "% of its " + name + " limit" + resets + "."});
        }
        return Optional.empty();
    }

    /** "1 day 9 h", "2 h 0 min", "12 min". */
    static String until(Duration d) {
        long days = d.toDays();
        long hours = d.toHoursPart();
        if (days > 0) {
            return days + (days == 1 ? " day " : " days ") + hours + " h";
        }
        if (d.toHours() > 0) {
            return d.toHours() + " h " + d.toMinutesPart() + " min";
        }
        return Math.max(1, d.toMinutes()) + " min";
    }

    private static long number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : 0;
    }

    private static double decimal(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : 0;
    }

    /** The final {"type":"result",…} line, or null if the CLI never produced one. */
    String resultLine() {
        return resultLine;
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }
}
