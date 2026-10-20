package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in installed-CLI checks; the isolation canary uses a local synthetic endpoint and no account. */
@EnabledIfEnvironmentVariable(named = "JUSTBUILD_TEST_GROK", matches = "1")
class NativeGrokProviderTest {
    @TempDir Path dir;
    static final String SYSTEM = "Return only the JSON object {\"ok\":true}.";
    static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},\"required\":[\"ok\"],\"additionalProperties\":false}";

    @Test void installedCliHasNoPlanningToolsOrPersonalInstructions() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonObject>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            requests.add(request);
            String reply = "{\"id\":\"canary\",\"created\":1234567890,\"model\":\"justbuild-canary\",\"object\":\"chat.completion.chunk\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":null}]}";
            String end = "{\"id\":\"canary\",\"created\":1234567890,\"model\":\"justbuild-canary\",\"object\":\"chat.completion.chunk\","
                    + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";
            byte[] bytes;
            boolean stream = request.has("stream") && request.get("stream").getAsBoolean();
            if (stream) bytes = ("data: " + reply + "\n\ndata: " + end + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            else bytes = "{\"id\":\"canary\",\"created\":1234567890,\"model\":\"justbuild-canary\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            Path original = Files.createDirectory(dir.resolve("original"));
            Files.writeString(original.resolve("AGENTS.md"), "PRIVATE-GROK-CANARY-MUST-NOT-APPEAR");
            Files.writeString(original.resolve("config.toml"), "[hooks]\n");
            var env = GrokCliProvider.isolatedContext(dir.resolve("isolated"), original.resolve("auth.json"));
            env.put("JUSTBUILD_CANARY_KEY", "synthetic");
            Files.writeString(Path.of(env.get("GROK_CONFIG_PATH")), GrokCliProvider.CONFIG + "\n[models]\ndefault = \"justbuild-canary\"\n"
                    + "[model.\"justbuild-canary\"]\nmodel = \"justbuild-canary\"\nbase_url = \"http://127.0.0.1:" + server.getAddress().getPort()
                    + "/v1\"\nenv_key = \"JUSTBUILD_CANARY_KEY\"\napi_backend = \"chat_completions\"\n");
            PlanRequest request = new PlanRequest(SYSTEM, "Reply {\"ok\":true}.", SCHEMA, "justbuild-canary", Duration.ofSeconds(40));
            Path prompt = dir.resolve("prompt.json"); Files.writeString(prompt, GrokCliProvider.prompt(request));
            Path exe = CommandLocator.resolve("", "grok", CommandLocator.defaultSearchDirs());
            var result = ProcessRunner.run(GrokCliProvider.command(exe, request, prompt), "", dir.resolve("isolated/work"), request.timeout(),
                    new CancellationToken(), null, env);
            assertEquals("{\"ok\":true}", GrokCliProvider.extract(result, ProgressListener.NONE));
            var planning = requests.stream().filter(r -> r.getAsJsonArray("messages").asList().stream()
                    .anyMatch(m -> m.getAsJsonObject().has("content") && m.getAsJsonObject().get("content").isJsonPrimitive()
                            && SYSTEM.equals(m.getAsJsonObject().get("content").getAsString()))).toList();
            assertEquals(1, planning.size());
            assertTrue(!planning.getFirst().has("tools") || planning.getFirst().getAsJsonArray("tools").isEmpty(), "Planning call exposed executable tools");
            assertFalse(requests.toString().contains("PRIVATE-GROK-CANARY"));
        } finally { server.stop(0); }
    }

    @Test @EnabledIfEnvironmentVariable(named = "JUSTBUILD_LIVE_GROK", matches = "1")
    void existingSubscriptionLoginAndStructuredResponse() throws Exception {
        var provider = new GrokCliProvider(""); var token = new CancellationToken();
        assertTrue(provider.check(token).contains("logged in"));
        assertFalse(provider.models(token).isEmpty());
        assertEquals("{\"ok\":true}", provider.complete(new PlanRequest(SYSTEM, "Reply {\"ok\":true}.", SCHEMA,
                "grok-4.7", Duration.ofSeconds(60)), token));
    }
}
