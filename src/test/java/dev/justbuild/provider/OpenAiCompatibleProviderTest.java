package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.config.JustBuildConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleProviderTest {
    static final String QUESTION = "{\"kind\":\"question\",\"question\":\"How tall?\",\"options\":null,"
            + "\"title\":null,\"summary\":null,\"operations\":null}";

    HttpServer server;
    final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    final List<String> auth = Collections.synchronizedList(new ArrayList<>());
    volatile int status = 200;
    volatile boolean streaming;
    volatile boolean rejectImages;
    /** When set, every reply is this many bytes on one line, as from a broken or hostile endpoint. */
    volatile int flood;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.createContext("/v1/models", exchange -> {
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            if (flood > 0) {
                flood(exchange, "application/json");
                return;
            }
            byte[] bytes = "{\"data\":[{\"id\":\"model-b\"},{\"id\":\"model-a\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        bodies.add(body);
        auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
        if (flood > 0) {
            flood(exchange, streaming ? "text/event-stream" : "application/json");
            return;
        }
        boolean strict = body.contains("\"json_schema\"");
        String reply;
        int code;
        if (streaming) {
            String events = OpenAiStreamTest.chunk("```json\n{\"kind\":\"build\",\"operations\":[{\"op\":\"set\"}")
                    + "\n\n" + OpenAiStreamTest.chunk(",{\"op\":\"fill\"}]}\n```") + "\n\ndata: [DONE]\n\n";
            byte[] bytes = events.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
            return;
        }
        if (rejectImages && body.contains("image_url")) {
            code = 400;
            reply = "{\"error\":{\"message\":\"This model does not support image input\"}}";
        } else if (status != 200) {
            code = status;
            reply = "{\"error\":{\"message\":\"Incorrect API key\"}}";
        } else if (strict) {
            code = 400;
            reply = "{\"error\":{\"message\":\"response_format json_schema is not supported\"}}";
        } else {
            code = 200;
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            message.addProperty("content", "```json\n" + QUESTION + "\n```");
            JsonObject choice = new JsonObject();
            choice.add("message", message);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject ok = new JsonObject();
            ok.add("choices", choices);
            reply = ok.toString();
        }
        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    void flood(HttpExchange exchange, String contentType) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, 0);
        byte[] chunk = "x".repeat(8192).getBytes(StandardCharsets.UTF_8);
        try {
            for (int sent = 0; sent < flood; sent += chunk.length) {
                exchange.getResponseBody().write(chunk);
            }
        } catch (IOException stoppedReading) {
            // The client hung up once it had read enough; that is the behavior under test.
        }
        exchange.close();
    }

    OpenAiCompatibleProvider provider(String apiKey) {
        JustBuildConfig.ModelProfile profile = new JustBuildConfig.ModelProfile("Test", "openai", "");
        profile.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
        profile.apiKey = apiKey;
        return new OpenAiCompatibleProvider(profile);
    }

    OpenAiCompatibleProvider provider(String apiKey, long maxResponseBytes) {
        JustBuildConfig.ModelProfile profile = new JustBuildConfig.ModelProfile("Test", "openai", "");
        profile.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
        profile.apiKey = apiKey;
        return new OpenAiCompatibleProvider(profile, maxResponseBytes);
    }

    @Test
    void stopsReadingAReplyThatIsTooLarge() {
        flood = 1024 * 1024;
        var e = assertThrows(ProviderException.class, () -> provider("k", 64 * 1024).complete(request("gpt-test"),
                new CancellationToken()));
        assertEquals("The endpoint sent more than 64 KB, so JustBuild stopped reading it.", e.getMessage());
    }

    @Test
    void stopsReadingAStreamThatIsTooLarge() {
        flood = 1024 * 1024;
        streaming = true;
        var e = assertThrows(ProviderException.class, () -> provider("k", 64 * 1024).complete(request("gpt-test"),
                new CancellationToken()));
        assertEquals("The endpoint sent more than 64 KB, so JustBuild stopped reading it.", e.getMessage());
    }

    @Test
    void stopsReadingAModelListThatIsTooLarge() {
        flood = 1024 * 1024;
        var e = assertThrows(ProviderException.class, () -> provider("k", 64 * 1024).models(new CancellationToken()));
        assertEquals("The endpoint sent more than 64 KB, so JustBuild stopped reading it.", e.getMessage());
    }

    static PlanRequest request(String model) {
        return new PlanRequest("SYS", "USER", "{\"type\":\"object\"}", model, Duration.ofSeconds(10));
    }

    @Test
    void listsModelsWithTheProfileKey() throws Exception {
        assertEquals(List.of("model-a", "model-b"), provider("sk-list").models(new CancellationToken()));
        assertEquals("Bearer sk-list", auth.get(0));
        assertTrue(provider("").check(new CancellationToken()).contains("(2 models)"));
        status = 401;
        assertThrows(ProviderException.class, () -> provider("bad").models(new CancellationToken()));
    }

    @Test
    void copiesTheProfile() throws Exception {
        JustBuildConfig.ModelProfile profile = new JustBuildConfig.ModelProfile("Test", "openai", "");
        profile.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(profile);
        profile.baseUrl = "http://127.0.0.1:1/nowhere";
        assertEquals(2, provider.models(new CancellationToken()).size());
    }

    @Test
    void fallsBackWhenStrictSchemaIsRejected() throws Exception {
        String text = provider("sk-test").complete(request("gpt-test"), new CancellationToken());
        assertTrue(text.contains("How tall?"));
        assertEquals(2, bodies.size());
        assertTrue(bodies.get(1).contains("\"json_object\""));
        assertEquals("Bearer sk-test", auth.get(0));
    }

    @Test
    void sendsTheProfileReasoningEffort() throws Exception {
        provider("sk-test").complete(request("gpt-test"), new CancellationToken());
        assertTrue(bodies.stream().noneMatch(b -> b.contains("reasoning_effort")));
        bodies.clear();
        JustBuildConfig.ModelProfile profile = new JustBuildConfig.ModelProfile("Test", "openai", "");
        profile.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
        profile.reasoningEffort = "none";
        new OpenAiCompatibleProvider(profile).complete(request("gpt-test"), new CancellationToken());
        assertFalse(bodies.isEmpty());
        assertTrue(bodies.stream().allMatch(b -> b.contains("\"reasoning_effort\":\"none\"")), bodies.toString());
    }

    @Test
    void omitsAuthHeaderWithoutKeyAndDoesNotRetryAuthErrors() {
        status = 401;
        var e = assertThrows(ProviderException.class, () -> provider("").complete(request("gpt-test"),
                new CancellationToken()));
        assertEquals("HTTP 401: Incorrect API key", e.getMessage());
        assertEquals(1, bodies.size());
        assertNull(auth.get(0));
    }

    @Test
    void readsServerSentEventsAndReportsOperations() throws Exception {
        streaming = true;
        java.util.List<Integer> ops = new java.util.concurrent.CopyOnWriteArrayList<>();
        String text = provider("k").complete(request("gpt-test"), new CancellationToken(), new ProgressListener() {
            @Override
            public void phase(Phase phase) {
            }

            @Override
            public void operations(int count) {
                ops.add(count);
            }
        });
        assertTrue(text.contains("\"op\":\"fill\""));
        assertEquals(java.util.List.of(1, 2), ops);
        assertTrue(bodies.get(0).contains("\"stream\":true"));
    }

    @Test
    void requiresAModel() {
        var e = assertThrows(ProviderException.class, () -> provider("k").complete(request(" "), new CancellationToken()));
        assertTrue(e.getMessage().contains("Settings → Pick…") && e.getMessage().contains("/justbuild models"));
        assertEquals(0, bodies.size());
    }

    @Test void unsupportedImagesFallBackOnceAndRetainTextEvidence() throws Exception {
        dev.justbuild.world.McBootstrap.init(); rejectImages = true;
        var volume = dev.justbuild.prompt.VoxelVolume.capture(new dev.justbuild.plan.InspectionRequest(
                new dev.justbuild.plan.Cell(0, 0, 0), new dev.justbuild.plan.Cell(0, 0, 0)), 1, true, c -> "minecraft:stone");
        var spec = dev.justbuild.relay.PromptSpec.newBuild("minecraft:overworld", "minecraft:plains",
                dev.justbuild.relay.PromptSpec.Placement.FRONT, null, null, "a marker", List.of(), 0, null, 100)
                .withFeedback(new dev.justbuild.prompt.PlanningFeedback(null, List.of(), volume, null, null, null));
        var request = new PlanRequest("SYS", "Keep these diagnostics", "{\"type\":\"object\"}", "text-model", Duration.ofSeconds(10), spec);
        assertTrue(provider("").complete(request, new CancellationToken()).contains("How tall?"));
        assertEquals(3, bodies.size(), "image rejection, schema rejection, then success");
        assertTrue(bodies.getFirst().contains("data:image/png;base64,"));
        for (String body : bodies.subList(1, bodies.size())) {
            assertFalse(body.contains("image_url")); assertTrue(body.contains("Keep these diagnostics"));
            assertTrue(body.contains("Images are unavailable"));
        }
    }
}
