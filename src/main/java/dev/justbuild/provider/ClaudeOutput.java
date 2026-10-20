package dev.justbuild.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/** Reads `claude -p --output-format json` output. */
final class ClaudeOutput {
    private ClaudeOutput() {
    }

    static String extract(ProcessResult result) throws ProviderException {
        JsonObject root = null;
        try {
            JsonElement element = JsonParser.parseString(result.stdout().trim());
            if (element.isJsonObject()) {
                root = element.getAsJsonObject();
            }
        } catch (JsonParseException ignored) {
            // handled below
        }
        if (root == null) {
            String all = result.stderr() + result.stdout();
            if (all.contains("unknown option")) {
                throw new ProviderException("Your Claude Code is too old for JustBuild (" + ProcessRunner.tail(all, 1)
                        + "). Update it with `claude update` or your package manager.");
            }
            String detail = ProcessRunner.tail(result.stderr(), 3);
            if (detail.isEmpty()) {
                detail = ProcessRunner.tail(result.stdout(), 3);
            }
            throw new ProviderException("Claude CLI exited with code " + result.exitCode() + ": "
                    + (detail.isEmpty() ? "no output" : detail));
        }
        JsonElement isError = root.get("is_error");
        if (isError != null && isError.isJsonPrimitive() && isError.getAsBoolean()) {
            throw new ProviderException("Claude: " + text(root, "result", "unknown error"));
        }
        JsonElement structured = root.get("structured_output");
        if (structured != null && structured.isJsonObject()) {
            return structured.toString();
        }
        String text = text(root, "result", "");
        if (!text.isBlank()) {
            return text;
        }
        throw new ProviderException("Claude returned no build plan");
    }

    private static String text(JsonObject root, String key, String fallback) {
        JsonElement e = root.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }
}
