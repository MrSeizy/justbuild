package dev.justbuild.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Base64;

/** Native Messages API content, including image blocks and structured JSON output. */
final class AnthropicMessages {
    private AnthropicMessages() {}

    static String endpoint(String baseUrl, String path) {
        String base = OpenAiMessages.endpoint(baseUrl, "");
        return base + (base.endsWith("/v1") ? "" : "/v1") + path;
    }

    static String requestBody(PlanRequest request, int maxTokens, boolean structured) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model().strip());
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("stream", true);
        body.addProperty("system", request.systemPrompt() + (structured ? "" :
                "\nReturn only JSON matching this schema:\n" + request.schemaJson()));
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", request.userContent());
        content.add(text);
        for (byte[] png : request.images()) {
            JsonObject source = new JsonObject();
            source.addProperty("type", "base64");
            source.addProperty("media_type", "image/png");
            source.addProperty("data", Base64.getEncoder().encodeToString(png));
            JsonObject image = new JsonObject();
            image.addProperty("type", "image");
            image.add("source", source);
            content.add(image);
        }
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.add("content", content);
        JsonArray messages = new JsonArray();
        messages.add(user);
        body.add("messages", messages);
        if (structured) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_schema");
            format.add("schema", JsonParser.parseString(request.schemaJson()));
            JsonObject output = new JsonObject();
            output.add("format", format);
            body.add("output_config", output);
        }
        return body.toString();
    }

    static String content(String body, ProgressListener progress) throws ProviderException {
        try {
            JsonObject message = JsonParser.parseString(body).getAsJsonObject();
            if (message.has("error")) throw error(message);
            checkStop(string(message, "stop_reason"));
            StringBuilder text = new StringBuilder();
            for (JsonElement block : message.getAsJsonArray("content")) {
                JsonObject object = block.getAsJsonObject();
                if (string(object, "type").equals("text")) text.append(string(object, "text"));
            }
            if (text.toString().isBlank()) throw new ProviderException("Endpoint returned an empty message");
            if (message.get("usage") instanceof JsonObject usage)
                progress.tokens(inputTokens(usage), count(usage, "output_tokens"));
            return text.toString();
        } catch (RuntimeException e) {
            throw new ProviderException("Endpoint returned an invalid Anthropic Messages response");
        }
    }

    static void checkStop(String reason) throws ProviderException {
        if (reason.equals("max_tokens"))
            throw new ProviderException("The model reached its output token limit. Raise Max tokens in this profile or request a smaller build.");
        if (reason.equals("refusal")) throw new ProviderException("The model refused this request.");
        if (!reason.equals("end_turn") && !reason.equals("stop_sequence"))
            throw new ProviderException("The model did not finish a text response (stop reason: "
                    + OpenAiMessages.abbreviate(reason) + ").");
    }

    static ProviderException error(JsonObject event) {
        return new ProviderException(OpenAiMessages.errorMessage(200, event.toString()));
    }

    static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    static long count(JsonObject usage, String key) {
        JsonElement value = usage.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? Math.max(0, value.getAsLong()) : 0;
    }

    static long inputTokens(JsonObject usage) {
        return count(usage, "input_tokens") + count(usage, "cache_creation_input_tokens")
                + count(usage, "cache_read_input_tokens");
    }
}
