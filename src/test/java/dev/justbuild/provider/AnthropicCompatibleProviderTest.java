package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.config.JustBuildConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnthropicCompatibleProviderTest {
    HttpServer server;
    java.util.concurrent.ExecutorService executor;
    final List<JsonObject> bodies = new CopyOnWriteArrayList<>();
    final List<String> keys = new CopyOnWriteArrayList<>();
    final List<String> versions = new CopyOnWriteArrayList<>();
    final List<String> paths = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile int modelStatus = 200;
    volatile boolean rejectSchema, rejectImages, paginate, stall;
    volatile String response = message("{\"ok\":true}", "end_turn");
    volatile String contentType = "application/json";
    final CountDownLatch bodyStarted = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/v1/messages", x -> {
            headers(x);
            JsonObject body = JsonParser.parseString(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            bodies.add(body);
            if (stall) {
                x.getResponseHeaders().add("Content-Type", "text/event-stream");
                x.sendResponseHeaders(200, 0);
                x.getResponseBody().write("event: ping\ndata: {\"type\":\"ping\"}\n\n".getBytes(StandardCharsets.UTF_8));
                x.getResponseBody().flush(); bodyStarted.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                x.close(); return;
            }
            if (status != 200) { reply(x, status, "{\"error\":{\"message\":\"fixture-secret-key\"}}", contentType); return; }
            if (rejectImages && body.toString().contains("\"image\"")) {
                reply(x, 400, "{\"error\":{\"message\":\"image input is unsupported\"}}", "application/json"); return;
            }
            if (rejectSchema && body.has("output_config")) {
                reply(x, 422, "{\"error\":{\"message\":\"output_config json_schema unsupported\"}}", "application/json"); return;
            }
            reply(x, 200, response, contentType);
        });
        server.createContext("/v1/models", x -> {
            headers(x);
            if (modelStatus != 200) { reply(x, modelStatus, "fixture-secret-key", "application/json"); return; }
            boolean second = x.getRequestURI().getRawQuery().contains("after_id=");
            reply(x, 200, paginate && !second ? "{\"data\":[{\"id\":\"b/1\"}],\"has_more\":true,\"last_id\":\"b/1\"}"
                    : "{\"data\":[{\"id\":\"a\"},{\"id\":\"b/1\"}],\"has_more\":false}", "application/json");
        });
        server.start();
    }

    @AfterEach void stop() { release.countDown(); server.stop(0); executor.close(); }

    void headers(HttpExchange x) {
        keys.add(String.valueOf(x.getRequestHeaders().getFirst("x-api-key")));
        versions.add(String.valueOf(x.getRequestHeaders().getFirst("anthropic-version")));
        assertNull(x.getRequestHeaders().getFirst("Authorization"));
        paths.add(x.getRequestURI().toString());
    }

    static void reply(HttpExchange x, int status, String body, String type) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", type);
        try { x.sendResponseHeaders(status, bytes.length); x.getResponseBody().write(bytes); }
        finally { x.close(); }
    }

    JustBuildConfig.ModelProfile profile() {
        var profile = new JustBuildConfig.ModelProfile("Test", "anthropic", "a");
        profile.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        profile.apiKey = "fixture-secret-key";
        profile.maxTokens = 16000;
        return profile;
    }

    static PlanRequest request(Duration timeout) {
        return new PlanRequest("SYSTEM", "USER", "{\"type\":\"object\"}", "a", timeout);
    }
    static PlanRequest request() { return request(Duration.ofSeconds(3)); }
    static String message(String text, String reason) {
        JsonObject block = new JsonObject(); block.addProperty("type", "text"); block.addProperty("text", text);
        return "{\"type\":\"message\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"private\"}," + block
                + "],\"stop_reason\":\"" + reason + "\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
    }
    static String event(String data) { return "data: " + data + "\n\n"; }
    static String delta(String text) {
        var delta = new JsonObject(); delta.addProperty("type", "text_delta"); delta.addProperty("text", text);
        return event("{\"type\":\"content_block_delta\",\"delta\":" + delta + "}");
    }
    static String ending(String reason) {
        return event("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + reason
                + "\"},\"usage\":{\"output_tokens\":12}}") + event("{\"type\":\"message_stop\"}");
    }

    @Test void nativeBodyHeadersAndProfileSnapshot() throws Exception {
        var p = profile(); var provider = new AnthropicCompatibleProvider(p);
        p.apiKey = "changed"; p.maxTokens = 1; p.baseUrl = "http://127.0.0.1:1";
        assertEquals("{\"ok\":true}", provider.complete(request(), new CancellationToken()));
        JsonObject body = bodies.getFirst();
        assertEquals("SYSTEM\nReturn only JSON matching this schema:\n{\"type\":\"object\"}", body.get("system").getAsString());
        assertEquals("a", body.get("model").getAsString());
        assertEquals(16000, body.get("max_tokens").getAsInt());
        assertTrue(body.get("stream").getAsBoolean());
        assertEquals("json_schema", body.getAsJsonObject("output_config").getAsJsonObject("format").get("type").getAsString());
        assertFalse(body.has("response_format")); assertFalse(body.has("reasoning_effort"));
        assertEquals(1, body.getAsJsonArray("messages").size());
        assertEquals(List.of("fixture-secret-key"), keys);
        assertEquals(List.of("2023-06-01"), versions);
        assertEquals(List.of("/v1/messages"), paths);
    }

    @Test void compatibleServicesUseTheirDocumentedAuthenticationAndMessagePaths() {
        for (String base : List.of("https://openrouter.ai/api/v1", "https://api.moonshot.ai/anthropic",
                "https://api.moonshot.cn/anthropic", "https://api.z.ai/api/anthropic",
                "https://token-plan.maas.qwencloudapi.com/apps/anthropic")) {
            var http = AnthropicMessages.requestBuilder(URI.create(AnthropicMessages.endpoint(base, "/messages")), " fixture-key ")
                    .build();
            assertEquals("Bearer fixture-key", http.headers().firstValue("Authorization").orElseThrow(), base);
            assertTrue(http.headers().firstValue("x-api-key").isEmpty(), base);
            assertTrue(http.uri().getPath().endsWith("/v1/messages"), base);
            assertFalse(http.uri().getPath().contains("/v1/v1/"), base);
        }
        for (String base : List.of("https://api.anthropic.com/v1", "https://api.deepseek.com/anthropic",
                "https://dashscope-intl.aliyuncs.com/apps/anthropic",
                "https://bedrock-runtime.us-east-1.amazonaws.com/anthropic", "https://custom.example/anthropic",
                "https://openrouter.ai.evil.example/api", "https://example.org/openrouter.ai")) {
            var http = AnthropicMessages.requestBuilder(URI.create(AnthropicMessages.endpoint(base, "/messages")), "fixture-key").build();
            assertEquals("fixture-key", http.headers().firstValue("x-api-key").orElseThrow(), base);
            assertTrue(http.headers().firstValue("Authorization").isEmpty(), base);
            assertEquals("2023-06-01", http.headers().firstValue("anthropic-version").orElseThrow());
        }
        var noKey = AnthropicMessages.requestBuilder(URI.create("https://openrouter.ai/api/v1/models"), " ").build();
        assertTrue(noKey.headers().firstValue("Authorization").isEmpty());
        assertTrue(noKey.headers().firstValue("x-api-key").isEmpty());
    }

    @Test void streamUsesOnlyTextAndCumulativeUsageAndRejectsTruncation() throws Exception {
        contentType = "text/event-stream";
        String chunks = event("{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"cache_creation_input_tokens\":3,\"cache_read_input_tokens\":5}}}")
                + event("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"private\"}}")
                + event("{\"type\":\"new_future_event\"}")
                + event("{\"type\":\"content_block_start\",\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
                + delta("{\"operations\":[{\"o") + delta("p\":\"set\"},{\"op\":\"fill\"}]}")
                + event("{\"type\":\"message_delta\",\"delta\":{},\"usage\":{\"output_tokens\":8}}");
        response = chunks + ending("end_turn");
        var phases = new CopyOnWriteArrayList<ProgressListener.Phase>();
        var operations = new CopyOnWriteArrayList<Integer>(); var usage = new CopyOnWriteArrayList<List<Long>>();
        String text = new AnthropicCompatibleProvider(profile()).complete(request(), new CancellationToken(), new ProgressListener() {
            public void phase(Phase phase) { phases.add(phase); }
            public void operations(int count) { operations.add(count); }
            public void tokens(long in, long out) { usage.add(List.of(in, out)); }
        });
        assertEquals("{\"operations\":[{\"op\":\"set\"},{\"op\":\"fill\"}]}", text);
        assertEquals(List.of(ProgressListener.Phase.THINKING, ProgressListener.Phase.WRITING), phases);
        assertEquals(2, operations.getLast()); assertEquals(List.of(List.of(18L, 12L)), usage);
        response = chunks;
        assertTrue(assertThrows(ProviderException.class, () -> new AnthropicCompatibleProvider(profile())
                .complete(request(), new CancellationToken())).getMessage().contains("before the response finished"));
        response = chunks + ending("max_tokens");
        assertTrue(assertThrows(ProviderException.class, () -> new AnthropicCompatibleProvider(profile())
                .complete(request(), new CancellationToken())).getMessage().contains("Raise Max tokens"));
        response = event("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}");
        assertTrue(assertThrows(ProviderException.class, () -> new AnthropicCompatibleProvider(profile())
                .complete(request(), new CancellationToken())).getMessage().contains("Overloaded"));
    }

    @Test void imagesAndSchemaFallBackWithoutDiscardingTextEvidence() throws Exception {
        dev.justbuild.world.McBootstrap.init(); rejectImages = true; rejectSchema = true;
        var volume = dev.justbuild.prompt.VoxelVolume.capture(new dev.justbuild.plan.InspectionRequest(
                new dev.justbuild.plan.Cell(0, 0, 0), new dev.justbuild.plan.Cell(0, 0, 0)), 1, true, c -> "minecraft:stone");
        var spec = dev.justbuild.relay.PromptSpec.newBuild("minecraft:overworld", "minecraft:plains",
                dev.justbuild.relay.PromptSpec.Placement.FRONT, null, null, "a marker", List.of(), 0, null, 100)
                .withFeedback(new dev.justbuild.prompt.PlanningFeedback(null, List.of(), volume, null, null, null));
        var request = new PlanRequest("SYS", "Keep diagnostics", "{\"type\":\"object\"}", "a", Duration.ofSeconds(3), spec);
        assertEquals("{\"ok\":true}", new AnthropicCompatibleProvider(profile()).complete(request, new CancellationToken()));
        assertEquals(3, bodies.size());
        var image = bodies.getFirst().getAsJsonArray("messages").get(0).getAsJsonObject().getAsJsonArray("content").get(1).getAsJsonObject();
        assertEquals("image", image.get("type").getAsString());
        assertEquals("image/png", image.getAsJsonObject("source").get("media_type").getAsString());
        byte[] png = java.util.Base64.getDecoder().decode(image.getAsJsonObject("source").get("data").getAsString());
        assertArrayEquals(new byte[] {(byte) 137, 80, 78, 71}, java.util.Arrays.copyOf(png, 4));
        assertFalse(bodies.getLast().has("output_config"));
        assertTrue(bodies.getLast().get("system").getAsString().contains(request.schemaJson()));
        assertTrue(bodies.subList(1, 3).stream().allMatch(b -> b.toString().contains("Keep diagnostics") && !b.toString().contains("\"image\"")));
    }

    @Test void authErrorsAreNotRetriedAndNoKeyHeaderWithoutAKey() {
        status = 401; var p = profile(); p.apiKey = "";
        assertThrows(ProviderException.class, () -> new AnthropicCompatibleProvider(p).complete(request(), new CancellationToken()));
        assertEquals(1, bodies.size()); assertEquals(List.of("null"), keys);
    }

    @Test void modelPaginationAndRootOrVersionedUrls() throws Exception {
        paginate = true; var p = profile(); p.baseUrl += "/v1/";
        assertEquals(List.of("a", "b/1"), new AnthropicCompatibleProvider(p).models(new CancellationToken()));
        assertEquals(List.of("/v1/models?limit=1000", "/v1/models?limit=1000&after_id=b%2F1"), paths);
        assertTrue(keys.stream().allMatch("fixture-secret-key"::equals));
        assertEquals("https://gateway.example/anthropic/v1/messages", AnthropicMessages.endpoint("https://gateway.example/anthropic/", "/messages"));
    }

    @Test void oversizedAndInvalidResponsesFailClearly() {
        response = "x".repeat(128 * 1024);
        assertTrue(assertThrows(ProviderException.class, () -> new AnthropicCompatibleProvider(profile(), 64 * 1024)
                .complete(request(), new CancellationToken())).getMessage().contains("more than 64 KB"));
        contentType = "text/event-stream";
        assertTrue(assertThrows(ProviderException.class, () -> new AnthropicCompatibleProvider(profile(), 64 * 1024)
                .complete(request(), new CancellationToken())).getMessage().contains("more than 64 KB"));
        contentType = "application/json"; response = message("partial", "max_tokens");
        assertTrue(assertThrows(ProviderException.class, () -> new AnthropicCompatibleProvider(profile())
                .complete(request(), new CancellationToken())).getMessage().contains("Raise Max tokens"));
        response = message("", "refusal");
        assertTrue(assertThrows(ProviderException.class, () -> new AnthropicCompatibleProvider(profile())
                .complete(request(), new CancellationToken())).getMessage().contains("refused"));
        response = "{}";
        assertThrows(ProviderException.class, () -> new AnthropicCompatibleProvider(profile()).complete(request(), new CancellationToken()));
    }

    @Test void cancellationInterruptsAStalledBody() throws Exception {
        stall = true; var token = new CancellationToken(); var failure = new AtomicReference<Throwable>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try { new AnthropicCompatibleProvider(profile()).complete(request(), token); }
            catch (Throwable e) { failure.set(e); }
        });
        assertTrue(bodyStarted.await(2, TimeUnit.SECONDS)); token.cancel();
        thread.join(2000); assertFalse(thread.isAlive(), "cancel left a blocked response reader");
        assertInstanceOf(CancelledException.class, failure.get());
    }

    @Test void deadlineInterruptsAStalledBody() {
        stall = true;
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            var failure = assertThrows(ProviderException.class, () -> new AnthropicCompatibleProvider(profile())
                    .complete(request(Duration.ofMillis(200)), new CancellationToken()));
            assertTrue(failure.getMessage().contains("No response"), failure.getMessage());
        });
    }

    @Test void diagnosticsUseNativeMessagesAndRedactFailures() throws Exception {
        var checks = ProfileDiagnostics.run(JustBuildConfig.defaults(), profile(), new CancellationToken());
        assertTrue(checks.stream().allMatch(c -> c.status() == ProfileDiagnostics.Status.PASS));
        modelStatus = 404;
        checks = ProfileDiagnostics.run(JustBuildConfig.defaults(), profile(), new CancellationToken());
        assertEquals(ProfileDiagnostics.Status.WARN, checks.getFirst().status());
        assertTrue(checks.getFirst().detail().contains("/messages"));
        assertEquals(ProfileDiagnostics.Status.PASS, checks.getLast().status());
        status = 401;
        checks = ProfileDiagnostics.run(JustBuildConfig.defaults(), profile(), new CancellationToken());
        assertEquals("Authentication", checks.getLast().title()); assertFalse(checks.toString().contains("fixture-secret-key"));
    }
}
