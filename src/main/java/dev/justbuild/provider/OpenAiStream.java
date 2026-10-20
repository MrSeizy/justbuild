package dev.justbuild.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.Iterator;
import java.util.stream.Stream;

/** Reads a server-sent-events /chat/completions stream, reporting operations as the plan arrives. */
final class OpenAiStream {
    private OpenAiStream() {
    }

    static String read(Stream<String> lines, ProgressListener progress) throws ProviderException {
        StringBuilder content = new StringBuilder();
        int operations = 0;
        boolean thinking = false;
        boolean writing = false;
        long[] usage = {0, 0};
        for (Iterator<String> it = lines.iterator(); it.hasNext(); ) {
            String line = it.next().strip();
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring(5).strip();
            if (data.equals("[DONE]")) {
                break;
            }
            JsonObject chunk;
            try {
                chunk = JsonParser.parseString(data).getAsJsonObject();
            } catch (JsonParseException | IllegalStateException e) {
                continue;
            }
            if (chunk.has("error") && chunk.get("error").isJsonObject()) {
                JsonElement message = chunk.getAsJsonObject("error").get("message");
                throw new ProviderException("Endpoint error: " + (message == null || !message.isJsonPrimitive()
                        ? "unknown" : OpenAiMessages.abbreviate(message.getAsString())));
            }
            if (chunk.get("usage") instanceof JsonObject reported) {
                usage = OpenAiMessages.usage(reported);
            }
            JsonArray choices = chunk.has("choices") && chunk.get("choices").isJsonArray()
                    ? chunk.getAsJsonArray("choices") : null;
            if (choices == null || choices.isEmpty() || !choices.get(0).getAsJsonObject().has("delta")) {
                continue;
            }
            JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
            JsonElement refusal = delta.get("refusal");
            if (refusal != null && refusal.isJsonPrimitive() && !refusal.getAsString().isBlank()) {
                throw new ProviderException("The model refused: " + OpenAiMessages.abbreviate(refusal.getAsString()));
            }
            // Reasoning models (DeepSeek, OpenRouter's "reasoning") can think for minutes before the first content.
            if (!thinking && !writing && (hasText(delta, "reasoning_content") || hasText(delta, "reasoning"))) {
                thinking = true;
                progress.phase(ProgressListener.Phase.THINKING);
            }
            JsonElement piece = delta.get("content");
            if (piece != null && piece.isJsonPrimitive() && !piece.getAsString().isEmpty()) {
                if (!writing) {
                    writing = true;
                    progress.phase(ProgressListener.Phase.WRITING);
                }
                int from = content.length() - 3;
                content.append(piece.getAsString());
                int found = ProgressListener.countOps(content, from);
                if (found > 0) {
                    operations += found;
                    progress.operations(operations);
                }
            }
        }
        if (content.toString().isBlank()) {
            throw new ProviderException("Endpoint returned an empty message");
        }
        progress.tokens(usage[0], usage[1]);
        return content.toString();
    }

    private static boolean hasText(JsonObject delta, String key) {
        JsonElement e = delta.get(key);
        return e != null && e.isJsonPrimitive() && !e.getAsString().isEmpty();
    }
}
