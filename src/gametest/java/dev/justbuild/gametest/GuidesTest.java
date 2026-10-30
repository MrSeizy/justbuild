package dev.justbuild.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

/**
 * A keyword-rich castle request starts with a menu and no references. The AI selects five guides; only those
 * texts reach planning and review, with no further menu. A second build where
 * the AI skips the guides must not offer the menu again in its review round.
 */
public final class GuidesTest implements FabricClientGameTest {
    static final String ASK = """
            {"kind":"guides","question":null,"options":null,"title":null,"summary":null,"operations":null,
             "guides":["castles","no-such-guide","towers","interiors","landscaping","glowing-ceilings"]}
            """;

    @Override
    public void runTest(ClientGameTestContext context) {
        List<String[]> calls = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean askFirst = new java.util.concurrent.atomic.AtomicBoolean(true);
        HttpServer stub = startStub(calls, askFirst);
        List<String[]> skipped;
        ConfigStore store = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("justbuild.json"));
        write(store, stub.getAddress().getPort());
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild a medieval castle with towers, furnished rooms and windows"));
            for (int i = 0; i < 300 && calls.size() < 3; i++) {
                context.waitTicks(5);
            }
            context.waitTicks(40);
            context.takeScreenshot("guides-chat");
            context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild cancel"));
            context.waitTicks(10);
            check(calls.size() == 3, "expected 3 AI calls (guides, plan, critique), got " + calls.size());
            skipped = new ArrayList<>(calls);
            calls.clear();
            askFirst.set(false);
            context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild a medieval castle with towers, furnished rooms and windows"));
            for (int i = 0; i < 300 && calls.size() < 2; i++) {
                context.waitTicks(5);
            }
            context.waitTicks(40);
            context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild cancel"));
            context.waitTicks(10);
        } finally {
            stub.stop(0);
            try {
                store.save(JustBuildConfig.defaults());
            } catch (Exception e) {
                throw new AssertionError("Could not reset " + store.file(), e);
            }
        }
        List<String[]> asked = skipped;
        check(asked.get(0)[0].contains("- timber-frame: "), "first call should offer the guide menu");
        check(!asked.get(0)[1].contains("Guides selected by you"), "the castle request must not receive automatically matched guides");
        check(!asked.get(1)[0].contains("- timber-frame: "), "second call should not offer guides again");
        check(!asked.get(0)[1].contains("- Style:"), "Auto must not impose a medieval house preset");
        for (String title : List.of("Castles and fortifications", "Towers and spires", "Interiors", "Landscaping and paths",
                "Lighting with lanterns and hidden lights")) {
            check(asked.get(1)[1].contains("## " + title), "planning should carry selected guide " + title);
            check(asked.get(2)[1].contains("## " + title), "the critique should retain selected guide " + title);
        }
        check(!asked.get(1)[1].contains("## Half-timbered walls"), "an unselected style guide must not be added");
        check(!asked.get(1)[1].contains("## Windows with frames"), "a keyword must not add an unselected guide");
        check(!asked.get(1)[1].contains("no-such-guide"), "unknown ids are dropped");
        check(calls.size() == 2, "expected 2 AI calls (plan, critique) when the AI skips guides, got " + calls.size());
        check(calls.get(0)[0].contains("- timber-frame: "), "a new build offers the menu again");
        check(!calls.get(1)[0].contains("- timber-frame: "), "the review round after a skip should not offer guides");
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    /** Records each call's {system, user} text; answers the first with a guide request (while askFirst) and the rest with a plan. */
    private static HttpServer startStub(List<String[]> calls, java.util.concurrent.atomic.AtomicBoolean askFirst) {
        try {
            HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.createContext("/v1/chat/completions", exchange -> {
                JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                JsonArray messages = body.getAsJsonArray("messages");
                calls.add(new String[] {messages.get(0).getAsJsonObject().get("content").getAsString(),
                        messages.get(1).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()});
                JsonObject message = new JsonObject();
                message.addProperty("role", "assistant");
                // The reviewer's system prompt identifies a critique call.
                boolean critique = calls.get(calls.size() - 1)[0].contains("You are an independent reviewer");
                message.addProperty("content", critique ? RelayTest.CRITIQUE_OK
                        : calls.size() == 1 && askFirst.get() ? ASK : PreviewTest.PLAN);
                JsonObject choice = new JsonObject();
                choice.add("message", message);
                JsonArray choices = new JsonArray();
                choices.add(choice);
                JsonObject reply = new JsonObject();
                reply.add("choices", choices);
                byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            stub.start();
            return stub;
        } catch (IOException e) {
            throw new AssertionError("Could not start the stub endpoint", e);
        }
    }

    /** Detail level detailed (one review round) with guides on; every plan previews. */
    private static void write(ConfigStore store, int port) {
        try {
            JustBuildConfig config = JustBuildConfig.defaults();
            JustBuildConfig.ModelProfile stub = new JustBuildConfig.ModelProfile("Stub", "openai", "stub-model");
            stub.baseUrl = "http://127.0.0.1:" + port + "/v1";
            List<JustBuildConfig.ModelProfile> profiles = new ArrayList<>(config.profiles);
            profiles.add(stub);
            config.replaceProfiles(profiles, "Stub");
            config.previewAbove = 0;
            config.detail = "detailed";
            config.guides = true;
            config.style = "auto";
            config.creativeDirection = false;
            store.save(config);
        } catch (Exception e) {
            throw new AssertionError("Could not write " + store.file(), e);
        }
    }
}
