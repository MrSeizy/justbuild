package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ProfileEdits;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProfileDiagnosticsTest {
    @Test void testsUnsavedDraftAndKeepsSecretsOutOfResults() throws Exception {
        var status = new AtomicInteger(200); var modelsStatus = new AtomicInteger(200);
        var response = new AtomicReference<>("{\"ok\":true}"); var body = new AtomicReference<>(""); var auth = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", x -> {
            byte[] bytes = (modelsStatus.get() == 200 ? "{\"data\":[{\"id\":\"draft-model\"}]}" : "secret-diagnostic-key").getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(modelsStatus.get(), bytes.length); x.getResponseBody().write(bytes); x.close();
        });
        server.createContext("/v1/chat/completions", x -> {
            body.set(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); auth.set(x.getRequestHeaders().getFirst("Authorization"));
            var m = new JsonObject(); m.addProperty("content", response.get()); var choice = new JsonObject(); choice.add("message", m);
            var choices = new com.google.gson.JsonArray(); choices.add(choice); var reply = new JsonObject(); reply.add("choices", choices);
            byte[] bytes = (status.get() == 200 ? reply.toString() : "secret-diagnostic-key").getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(status.get(), bytes.length); x.getResponseBody().write(bytes); x.close();
        }); server.start();
        try {
            var cfg = JustBuildConfig.defaults(); var saved = new JustBuildConfig.ModelProfile("Saved", "openai", "old-model");
            saved.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; saved.apiKey = "secret-diagnostic-key";
            cfg.replaceProfiles(List.of(saved), saved.name);
            var draft = ProfileEdits.resolve(cfg.profiles, new ProfileEdits.Edit("Saved", "Unsaved", "openai", "draft-model", saved.baseUrl, null));
            var checks = ProfileDiagnostics.run(cfg, draft, new CancellationToken());
            assertEquals(3, checks.size()); assertTrue(checks.stream().allMatch(c -> c.status() == ProfileDiagnostics.Status.PASS));
            assertTrue(body.get().contains("draft-model")); assertEquals("Bearer secret-diagnostic-key", auth.get());
            assertEquals("old-model", cfg.active().model); assertEquals("Saved", cfg.active().name);
            assertFalse(checks.toString().contains("secret-diagnostic-key"));
            response.set("{\"ok\":\"true\"}"); assertEquals(ProfileDiagnostics.Status.FAIL, ProfileDiagnostics.run(cfg, draft, new CancellationToken()).getLast().status());
            response.set("{\"ok\":true}"); modelsStatus.set(404);
            checks = ProfileDiagnostics.run(cfg, draft, new CancellationToken()); assertEquals(ProfileDiagnostics.Status.WARN, checks.getFirst().status()); assertEquals(ProfileDiagnostics.Status.PASS, checks.getLast().status());
            status.set(401); checks = ProfileDiagnostics.run(cfg, draft, new CancellationToken());
            assertEquals("Authentication", checks.getLast().title()); assertFalse(checks.toString().contains("secret-diagnostic-key"));
            status.set(404); assertEquals("Endpoint or model", ProfileDiagnostics.run(cfg, draft, new CancellationToken()).getLast().title());
            status.set(429); assertEquals("Service quota", ProfileDiagnostics.run(cfg, draft, new CancellationToken()).getLast().title());
            var token = new CancellationToken(); token.cancel(); assertThrows(CancelledException.class, () -> ProfileDiagnostics.run(cfg, draft, token));
        } finally { server.stop(0); }
    }
    @Test void cancellationBetweenChecksCannotStartACompletion() {
        var token = new CancellationToken();
        BuildProvider provider = new BuildProvider() {
            public String id() { return "openai"; } public String displayName() { return "test"; }
            public String check(CancellationToken t) { return ""; }
            public List<String> models(CancellationToken t) { t.cancel(); return List.of("m"); }
            public String complete(PlanRequest r, CancellationToken t, ProgressListener p) { fail("Cancelled test called the model"); return ""; }
        };
        assertThrows(CancelledException.class, () -> ProfileDiagnostics.run(provider, "m", token));
    }
}
