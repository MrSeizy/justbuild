package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OpenAiMessagesTest {
    static final PlanRequest REQUEST = new PlanRequest("SYS", "USER", "{\"type\":\"object\"}", "m", Duration.ofSeconds(5));

    @Test
    void requestBodies() {
        JsonObject strict = JsonParser.parseString(OpenAiMessages.requestBody(REQUEST, "gpt-x",
                OpenAiMessages.Format.JSON_SCHEMA)).getAsJsonObject();
        assertEquals("gpt-x", strict.get("model").getAsString());
        assertEquals("system", strict.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("USER", strict.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
        JsonObject format = strict.getAsJsonObject("response_format");
        assertEquals("json_schema", format.get("type").getAsString());
        assertTrue(format.getAsJsonObject("json_schema").get("strict").getAsBoolean());
        assertEquals("object", format.getAsJsonObject("json_schema").getAsJsonObject("schema").get("type").getAsString());

        JsonObject loose = JsonParser.parseString(OpenAiMessages.requestBody(REQUEST, "gpt-x",
                OpenAiMessages.Format.JSON_OBJECT)).getAsJsonObject();
        assertEquals("json_object", loose.getAsJsonObject("response_format").get("type").getAsString());
        JsonObject none = JsonParser.parseString(OpenAiMessages.requestBody(REQUEST, "gpt-x",
                OpenAiMessages.Format.NONE)).getAsJsonObject();
        assertFalse(none.has("response_format"));
        assertTrue(none.getAsJsonObject("stream_options").get("include_usage").getAsBoolean());
        JsonObject plain = JsonParser.parseString(OpenAiMessages.requestBody(REQUEST, "gpt-x",
                OpenAiMessages.Format.NONE, false)).getAsJsonObject();
        assertFalse(plain.has("stream_options"));
        assertFalse(plain.has("reasoning_effort"), "left to the endpoint unless the profile sets it");
        JsonObject quick = JsonParser.parseString(OpenAiMessages.requestBody(REQUEST, "gpt-x",
                OpenAiMessages.Format.JSON_OBJECT, true, "none")).getAsJsonObject();
        assertEquals("none", quick.get("reasoning_effort").getAsString());
        assertArrayEquals(new long[] {10, 4}, OpenAiMessages.usage(
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4}}"));
        assertArrayEquals(new long[] {0, 0}, OpenAiMessages.usage("{\"choices\":[]}"));
    }

    @Test
    void readsContent() throws Exception {
        assertEquals("{\"kind\":\"build\"}", OpenAiMessages.content(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"kind\\\":\\\"build\\\"}\"}}]}"));
        var refused = assertThrows(ProviderException.class, () -> OpenAiMessages.content(
                "{\"choices\":[{\"message\":{\"content\":null,\"refusal\":\"No.\"}}]}"));
        assertEquals("The model refused: No.", refused.getMessage());
        var odd = assertThrows(ProviderException.class, () -> OpenAiMessages.content("[]"));
        assertTrue(odd.getMessage().startsWith("Endpoint returned an unexpected response"));
    }

    @Test
    void errorMessagesAndEndpoints() {
        assertEquals("HTTP 401: Incorrect API key", OpenAiMessages.errorMessage(401,
                "{\"error\":{\"message\":\"Incorrect API key\",\"type\":\"invalid_request_error\"}}"));
        assertEquals("HTTP 502: Bad gateway", OpenAiMessages.errorMessage(502, "Bad gateway"));
        assertEquals("http://localhost:1234/v1/chat/completions",
                OpenAiMessages.endpoint(" http://localhost:1234/v1// ", "/chat/completions"));
    }

    @Test
    void endpointErrorsAndRefusalsStayBounded() {
        String huge = "x".repeat(20_000);
        assertTrue(OpenAiMessages.errorMessage(400, "{\"error\":{\"message\":\"" + huge + "\"}}").length() < 250);
        var refusal = assertThrows(ProviderException.class, () -> OpenAiMessages.content(
                "{\"choices\":[{\"message\":{\"refusal\":\"" + huge + "\"}}]}"));
        assertTrue(refusal.getMessage().length() < 250);
        for (String event : java.util.List.of("{\"error\":{\"message\":\"" + huge + "\"}}",
                "{\"choices\":[{\"delta\":{\"refusal\":\"" + huge + "\"}}]}")) {
            var error = assertThrows(ProviderException.class, () -> OpenAiStream.read(
                    java.util.stream.Stream.of("data: " + event), new RecordingListener()));
            assertTrue(error.getMessage().length() < 250);
        }
    }
}
