package dev.justbuild.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

/**
 * A plan held as a preview shows as a see-through box with an outline and a label (screenshots), and disappears when
 * cancelled. A stub OpenAI-compatible endpoint plays the AI so nothing real is called.
 */
public final class PreviewTest implements FabricClientGameTest {
    static final String PLAN = """
            {"kind":"build","question":null,"options":null,"title":"Preview Test Hall",
             "summary":"A 7 by 5 by 7 block of stone bricks.",
             "operations":[
               {"op":"fill","block":"stone_bricks","from":[-3,0,-8],"to":[3,4,-2],"radius":null,"height":null,"hollow":null}]}
            """;

    @Override
    public void runTest(ClientGameTestContext context) {
        HttpServer stub = startStub();
        ConfigStore store = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("justbuild.json"));
        write(store, stub.getAddress().getPort());
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild a stone hall"));
            context.waitTicks(60);
            context.takeScreenshot("preview-box");
            context.runOnClient(mc -> {
                mc.player.setYRot(mc.player.getYRot() + 40);
                mc.player.setXRot(20);
            });
            context.waitTicks(10);
            context.takeScreenshot("preview-box-angle");
            context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild cancel"));
            context.waitTicks(20);
            context.takeScreenshot("preview-cancelled");
        } finally {
            stub.stop(0);
            try {
                store.save(JustBuildConfig.defaults());
            } catch (Exception e) {
                throw new AssertionError("Could not reset " + store.file(), e);
            }
        }
    }

    private static HttpServer startStub() {
        try {
            HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                JsonObject message = new JsonObject();
                message.addProperty("role", "assistant");
                message.addProperty("content", PLAN);
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

    /** Every plan previews (previewAbove 0); the stub is the active profile; no review rounds. */
    private static void write(ConfigStore store, int port) {
        try {
            JustBuildConfig config = JustBuildConfig.defaults();
            JustBuildConfig.ModelProfile stub = new JustBuildConfig.ModelProfile("Stub", "openai", "stub-model");
            stub.baseUrl = "http://127.0.0.1:" + port + "/v1";
            List<JustBuildConfig.ModelProfile> profiles = new ArrayList<>(config.profiles);
            profiles.add(stub);
            config.replaceProfiles(profiles, "Stub");
            config.previewAbove = 0;
            config.detail = "quick";
            config.creativeDirection = false;
            store.save(config);
        } catch (Exception e) {
            throw new AssertionError("Could not write " + store.file(), e);
        }
    }
}
