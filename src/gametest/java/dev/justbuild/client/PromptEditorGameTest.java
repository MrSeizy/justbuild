package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import dev.justbuild.net.BuildRequestPayload;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/** Real widgets and network dispatch: multiline text, per-build drafts, reusable prompts and all request modes. */
public final class PromptEditorGameTest implements FabricClientGameTest {
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private static final String LONG = "Build a small marker.\n" + "Use a solid foundation and leave the entrance clear. ".repeat(65).stripTrailing();
    @Override public void runTest(ClientGameTestContext context) {
        var dir = FabricLoader.getInstance().getConfigDir(); var configFile = dir.resolve("justbuild.json");
        var prompts = dir.resolve("justbuild-prompts.json");
        byte[] oldConfig = read(configFile), oldPrompts = read(prompts);
        HttpServer stub = stub();
        try {
            Files.deleteIfExists(prompts);
            var config = JustBuildConfig.defaults(); var profile = new JustBuildConfig.ModelProfile("Stub", "openai", "stub");
            profile.baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1";
            config.replaceProfiles(List.of(profile), "Stub"); config.detail = "quick"; config.creativeDirection = false; config.previewAbove = 0;
            new ConfigStore(configFile).save(config);
            try (var world = context.worldBuilder().create()) {
                world.getClientLevel().waitForChunksRender();
                context.runOnClient(mc -> {
                    var parent = new JustBuildScreen();
                    mc.setScreen(new PromptEditorScreen(parent, null, null, 1, () -> {}));
                    editor(mc.screen).setValue(LONG);
                    var widget = editor(mc.screen);
                    widget.keyPressed(new KeyEvent(GLFW.GLFW_KEY_HOME, 0, 0));
                    mc.screen.resize(320, 240);
                    check(editor(mc.screen).getValue().equals(LONG) && ((dev.justbuild.mixin.client.MultiLineEditBoxAccessor) editor(mc.screen)).justbuild$textField().cursor()
                            == ((dev.justbuild.mixin.client.MultiLineEditBoxAccessor) widget).justbuild$textField().cursor(), "resize lost text or cursor");
                    String key = PromptDrafts.key(null, null);
                    check(PromptDrafts.get(key).equals(LONG), "new-build draft missing");
                    PromptDrafts.put(PromptDrafts.key("0123abcd", null), "A different building");
                    check(PromptDrafts.get(key).equals(LONG), "one build replaced another's draft");
                    check(!RelaySeen.snapshot().requests().contains(LONG), "typing armed a relay request");
                    var expanded = mc.screen;
                    expanded.onClose();
                    var compact = mc.screen.children().stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance)
                            .map(net.minecraft.client.gui.components.EditBox.class::cast).filter(e -> e.getMessage().getString().equals("Request")).findFirst().orElseThrow();
                    compact.setFocused(true); compact.charTyped(new net.minecraft.client.input.CharacterEvent('!'));
                    check(PromptDrafts.get(key).equals(LONG), "typing in the compact field flattened a multiline draft");
                    mc.setScreen(expanded);
                    check(editor(mc.screen).getValue().equals(LONG), "reopening the expanded editor lost line breaks");
                });
                context.takeScreenshot("multiline-request-editor");
                context.clickScreenButton("Save prompt"); context.clickScreenButton("Reuse prompt…");
                context.clickScreenButton("Build a small marker.");
                check(context.computeOnClient(mc -> editor(mc.screen).getValue().equals(LONG)), "reusing a prompt changed its text");
                context.clickScreenButton("Send request");
                context.waitFor(mc -> ClientState.question() != null, 800);
                check(bodies.getFirst().contains(LONG), "multiline build request was truncated");
                String question = context.computeOnClient(mc -> ClientState.question());
                String answer = "Use stone.\n" + "Keep it low and rectangular. ".repeat(70).stripTrailing();
                context.runOnClient(mc -> {
                    mc.setScreen(new PromptEditorScreen(new JustBuildScreen(), null, question, 1, () -> {}));
                    editor(mc.screen).setValue(answer);
                    mc.screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, GLFW.GLFW_MOD_CONTROL));
                });
                context.waitFor(mc -> ClientState.preview() != null, 800);
                check(bodies.get(1).contains(answer), "long answer did not reach the provider");
                command(context, "confirm");
                context.waitFor(mc -> !ClientState.builds().isEmpty() && ClientState.preview() == null, 800);
                String id = context.computeOnClient(mc -> ClientState.builds().getFirst().id());
                String edit = "Replace it with gold.\n" + "Keep its footprint. ".repeat(80).stripTrailing();
                context.runOnClient(mc -> {
                    String error = PromptSender.send(id, null, 1, edit); check(error == null, "edit was refused: " + error);
                });
                context.waitFor(mc -> ClientState.preview() != null, 800);
                check(bodies.get(2).contains(edit), "long edit was truncated");
                command(context, "confirm"); context.waitFor(mc -> ClientState.preview() == null, 800);
                context.runOnClient(mc -> {
                    String error = PromptSender.send(id, null, 2, LONG); check(error == null, "alternatives refused: " + error);
                });
                context.waitFor(mc -> ClientState.preview() != null && ClientState.preview().options().size() == 2, 1000);
                check(bodies.get(3).contains(LONG) && bodies.get(4).contains(LONG), "long alternatives lost the original request");
                check(context.computeOnClient(mc -> RelaySeen.snapshot().requests().contains(dev.justbuild.prompt.AlternativeRequests.variant(LONG, 2, 2))),
                        "only the submitted variants should authorize relay requests");
                context.runOnClient(mc -> {
                    String error = PromptSender.send(null, question, 1, answer); check(error != null, "a stale question was answered");
                    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                            new BuildRequestPayload("answer", "", 1, answer, BuildRequestPayload.questionKey(question)));
                });
                context.waitTicks(10);
                check(bodies.size() == 5, "a stale answer reached the provider");
                context.runOnClient(mc -> { RelaySeen.clear(); check(PromptDrafts.get(PromptDrafts.key("0123abcd", null)).equals("A different building"),
                        "clearing connection consent erased an unrelated draft"); mc.setScreen(null); });
            }
            context.runOnClient(mc -> {
                String unavailable = "A request entered while the server is unavailable";
                String key = PromptDrafts.key(null, null); PromptDrafts.put(key, unavailable);
                check(PromptSender.send(null, null, 1, unavailable) != null, "an unavailable channel accepted a request");
                check(PromptDrafts.get(key).equals(unavailable) && !RelaySeen.snapshot().requests().contains(unavailable),
                        "an unavailable channel consumed the draft or granted consent");
            });
        } catch (Exception e) { throw new AssertionError(e); }
        finally { stub.stop(0); restore(configFile, oldConfig); restore(prompts, oldPrompts); }
    }
    private HttpServer stub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); AtomicInteger calls = new AtomicInteger();
            server.createContext("/v1/chat/completions", exchange -> {
                var request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                StringBuilder text = new StringBuilder(); request.getAsJsonArray("messages").forEach(m -> {
                    var content = m.getAsJsonObject().get("content");
                    if (content instanceof JsonArray parts) parts.forEach(p -> { if (p.getAsJsonObject().has("text")) text.append(p.getAsJsonObject().get("text").getAsString()); });
                    else text.append(content.getAsString());
                });
                bodies.add(text.toString()); int call = calls.getAndIncrement();
                String content = call == 0 ? "{\"kind\":\"question\",\"question\":\"Which material?\",\"options\":[\"Stone\",\"Wood\"]}"
                        : "{\"kind\":\"build\",\"title\":\"Editor marker\",\"summary\":\"\",\"operations\":[{\"op\":\"fill\",\"block\":\""
                        + (call == 1 ? "stone" : call == 2 ? "gold_block" : "bricks") + "\",\"from\":[0,0,2],\"to\":[2,0,4]}]}";
                JsonObject message = new JsonObject(); message.addProperty("content", content); JsonObject choice = new JsonObject(); choice.add("message", message);
                JsonArray choices = new JsonArray(); choices.add(choice); JsonObject reply = new JsonObject(); reply.add("choices", choices);
                byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start(); return server;
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static MultiLineEditBox editor(net.minecraft.client.gui.screens.Screen screen) {
        return screen.children().stream().filter(MultiLineEditBox.class::isInstance).map(MultiLineEditBox.class::cast).findFirst().orElseThrow();
    }
    private static void command(ClientGameTestContext c, String text) { c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + text)); c.waitTicks(10); }
    private static byte[] read(java.nio.file.Path p) { try { return Files.exists(p) ? Files.readAllBytes(p) : null; } catch (Exception e) { throw new AssertionError(e); } }
    private static void restore(java.nio.file.Path p, byte[] bytes) { try { if (bytes == null) Files.deleteIfExists(p); else Files.write(p, bytes); } catch (Exception e) { throw new AssertionError(e); } }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
