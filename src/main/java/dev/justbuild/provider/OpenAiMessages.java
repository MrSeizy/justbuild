package dev.justbuild.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Request bodies and response parsing for /chat/completions. */
final class OpenAiMessages {
    enum Format { JSON_SCHEMA, JSON_OBJECT, NONE }

    private OpenAiMessages() {
    }

    static String requestBody(PlanRequest request, String model, Format format) {
        return requestBody(request, model, format, true);
    }

    static String requestBody(PlanRequest request, String model, Format format, boolean includeUsage) {
        return requestBody(request, model, format, includeUsage, null);
    }

    /**
     * {@code includeUsage} asks a streaming endpoint for a final chunk with token counts; a non-null
     * {@code reasoningEffort} is sent as reasoning_effort (thinking models may otherwise think for minutes first).
     */
    static String requestBody(PlanRequest request, String model, Format format, boolean includeUsage,
            String reasoningEffort) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", true);
        if (reasoningEffort != null) {
            body.addProperty("reasoning_effort", reasoningEffort);
        }
        if (includeUsage) {
            JsonObject options = new JsonObject();
            options.addProperty("include_usage", true);
            body.add("stream_options", options);
        }
        JsonArray messages = new JsonArray();
        messages.add(message("system", request.systemPrompt()));
        JsonObject user = message("user", request.userContent());
        var images = request.images();
        if (!images.isEmpty()) {
            JsonArray content = new JsonArray(); JsonObject text = new JsonObject();
            text.addProperty("type", "text"); text.addProperty("text", request.userContent()); content.add(text);
            for (byte[] png : images) {
                JsonObject image = new JsonObject(); image.addProperty("type", "image_url"); JsonObject url = new JsonObject();
                url.addProperty("url", "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png));
                image.add("image_url", url); content.add(image);
            }
            user.add("content", content);
        }
        messages.add(user);
        body.add("messages", messages);
        switch (format) {
            case JSON_SCHEMA -> {
                JsonObject schema = new JsonObject();
                schema.addProperty("name", "build_plan");
                schema.addProperty("strict", true);
                schema.add("schema", JsonParser.parseString(request.schemaJson()));
                JsonObject responseFormat = new JsonObject();
                responseFormat.addProperty("type", "json_schema");
                responseFormat.add("json_schema", schema);
                body.add("response_format", responseFormat);
            }
            case JSON_OBJECT -> {
                JsonObject responseFormat = new JsonObject();
                responseFormat.addProperty("type", "json_object");
                body.add("response_format", responseFormat);
            }
            case NONE -> {
            }
        }
        return body.toString();
    }

    /** {prompt tokens, completion tokens} from a response body; zeros when it has no usage. */
    static long[] usage(String responseBody) {
        try {
            if (JsonParser.parseString(responseBody).getAsJsonObject().get("usage") instanceof JsonObject usage) {
                return usage(usage);
            }
        } catch (RuntimeException ignored) {
            // no usage
        }
        return new long[] {0, 0};
    }

    static long[] usage(JsonObject usage) {
        return new long[] {count(usage, "prompt_tokens"), count(usage, "completion_tokens")};
    }

    private static long count(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : 0;
    }

    static String content(String responseBody) throws ProviderException {
        JsonObject message;
        try {
            JsonArray choices = JsonParser.parseString(responseBody).getAsJsonObject().getAsJsonArray("choices");
            message = choices == null || choices.isEmpty() ? null
                    : choices.get(0).getAsJsonObject().getAsJsonObject("message");
        } catch (RuntimeException e) {
            message = null;
        }
        if (message == null) {
            throw new ProviderException("Endpoint returned an unexpected response: " + abbreviate(responseBody));
        }
        JsonElement content = message.get("content");
        if (content != null && content.isJsonPrimitive() && !content.getAsString().isBlank()) {
            return content.getAsString();
        }
        JsonElement refusal = message.get("refusal");
        if (refusal != null && refusal.isJsonPrimitive()) {
            throw new ProviderException("The model refused: " + abbreviate(refusal.getAsString()));
        }
        throw new ProviderException("Endpoint returned an empty message");
    }

    static String errorMessage(int status, String body) {
        try {
            JsonObject error = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
            if (error != null && error.has("message")) {
                return "HTTP " + status + ": " + abbreviate(error.get("message").getAsString());
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        return "HTTP " + status + ": " + abbreviate(body);
    }

    static String endpoint(String baseUrl, String path) {
        String base = baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    static String abbreviate(String text) {
        String s = text == null ? "" : text.strip();
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }
}
