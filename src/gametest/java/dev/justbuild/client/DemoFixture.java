package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.loader.api.FabricLoader;

/** Loopback-only recording provider; snapshots and restores the test client's profile and saved prompts. */
final class DemoFixture implements AutoCloseable {
    record Answer(String text, CountDownLatch started, CountDownLatch release) { }
    private HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger unexpectedCalls = new AtomicInteger();
    private final Queue<Answer> answers = new ConcurrentLinkedQueue<>();
    private final Queue<Answer> outstanding = new ConcurrentLinkedQueue<>();
    private final Path configFile = FabricLoader.getInstance().getConfigDir().resolve("justbuild.json");
    private final Path promptsFile = FabricLoader.getInstance().getConfigDir().resolve("justbuild-prompts.json");
    private final byte[] configBefore = read(configFile), promptsBefore = read(promptsFile);
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    DemoFixture() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/v1/models", x -> send(x, 200, "{\"data\":[{\"id\":\"example-plan\"},{\"id\":\"example-fast\"}]}"));
            server.createContext("/v1/chat/completions", x -> {
                try {
                    String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String model = JsonParser.parseString(body).getAsJsonObject().get("model").getAsString();
                    if (model.equals("missing-model")) { send(x, 404, "{\"error\":\"Unknown fixture model\"}"); return; }
                    String response;
                    if (body.contains("This is a connection test.")) response = "{\"ok\":true}";
                    else {
                        String reviewed = StubReview.draft(body);
                        if (reviewed != null) response = reviewed;
                        else {
                            calls.incrementAndGet();
                            Answer next = answers.poll();
                            if (next == null) { unexpectedCalls.incrementAndGet(); send(x, 500, "{\"error\":\"Unexpected demo planning request\"}"); return; }
                            next.started.countDown();
                            if (!next.release.await(60, TimeUnit.SECONDS)) { send(x, 504, "{}"); return; }
                            response = next.text;
                        }
                    }
                    JsonObject message = new JsonObject(); message.addProperty("content", response);
                    JsonObject choice = new JsonObject(); choice.add("message", message);
                    JsonArray choices = new JsonArray(); choices.add(choice);
                    JsonObject result = new JsonObject(); result.add("choices", choices);
                    JsonObject usage = new JsonObject(); usage.addProperty("prompt_tokens", 1000); usage.addProperty("completion_tokens", 200);
                    result.add("usage", usage); send(x, 200, result.toString());
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); x.close(); }
                catch (Exception e) { x.close(); }
            });
            server.start();
            Files.deleteIfExists(promptsFile);
            JustBuildConfig config = JustBuildConfig.defaults();
            var primary = new JustBuildConfig.ModelProfile("Demo", "openai", "example-plan");
            var second = new JustBuildConfig.ModelProfile("Second profile", "openai", "example-fast");
            primary.baseUrl = second.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            config.replaceProfiles(List.of(primary, second), primary.name);
            config.detail = "quick"; config.creativeDirection = false; config.guides = false;
            config.previewAbove = 0; config.blocksPerTick = 12; config.maxQuestions = 0;
            config.playerAi = "require";
            new ConfigStore(configFile).save(config);
        } catch (Exception e) {
            if (server != null) server.stop(0);
            executor.shutdownNow(); restore(configFile, configBefore); restore(promptsFile, promptsBefore);
            throw new AssertionError(e);
        }
    }

    Answer answer(String plan, boolean hold) {
        Answer answer = new Answer(plan, new CountDownLatch(1), new CountDownLatch(hold ? 1 : 0));
        answers.add(answer); outstanding.add(answer); return answer;
    }
    void answer(String plan) { answer(plan, false); }
    void verify() {
        if (unexpectedCalls.get() != 0 || !answers.isEmpty())
            throw new AssertionError("Demo provider calls did not match the scenario: " + unexpectedCalls.get() + " unexpected, " + answers.size() + " unused");
    }
    @Override public void close() {
        outstanding.forEach(a -> a.release.countDown()); server.stop(0); executor.shutdownNow();
        restore(configFile, configBefore); restore(promptsFile, promptsBefore);
    }
    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String json) throws java.io.IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
        finally { exchange.close(); }
    }
    private static byte[] read(Path p) { try { return Files.exists(p) ? Files.readAllBytes(p) : null; } catch (Exception e) { throw new AssertionError(e); } }
    private static void restore(Path p, byte[] content) { try { if (content == null) Files.deleteIfExists(p); else Files.write(p, content); } catch (Exception e) { throw new AssertionError(e); } }
}
