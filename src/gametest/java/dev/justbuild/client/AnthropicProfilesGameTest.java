package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.config.ConfigStore;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.Operation;
import dev.justbuild.plan.PlanWriter;
import dev.justbuild.provider.ProfileDiagnostics;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Native API setup through real widgets and packets, then planning through to a confirmed build. */
public final class AnthropicProfilesGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var file = FabricLoader.getInstance().getConfigDir().resolve("justbuild.json");
        byte[] original = read(file);
        HttpServer stub = null;
        var bodies = new CopyOnWriteArrayList<JsonObject>();
        var keys = new CopyOnWriteArrayList<String>();
        try {
            stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.createContext("/v1/models", x -> {
                keys.add(String.valueOf(x.getRequestHeaders().getFirst("x-api-key")));
                byte[] bytes = "{\"data\":[{\"id\":\"native-model\"}],\"has_more\":false}".getBytes(StandardCharsets.UTF_8);
                x.sendResponseHeaders(200, bytes.length); x.getResponseBody().write(bytes); x.close();
            });
            stub.createContext("/v1/messages", x -> {
                keys.add(String.valueOf(x.getRequestHeaders().getFirst("x-api-key")));
                JsonObject body = JsonParser.parseString(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                bodies.add(body);
                String text = body.get("system").getAsString().contains("connection test") ? "{\"ok\":true}"
                        : PlanWriter.write(new BuildPlan("Native API paving", "A stone pad", List.of(Operation.fill(
                                "minecraft:stone", new Cell(-1, 0, -1), new Cell(1, 0, 1), false))));
                JsonObject block = new JsonObject(); block.addProperty("type", "text"); block.addProperty("text", text);
                JsonArray content = new JsonArray(); content.add(block);
                JsonObject reply = new JsonObject(); reply.addProperty("type", "message"); reply.add("content", content);
                reply.addProperty("stop_reason", "end_turn");
                byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                x.getResponseHeaders().add("Content-Type", "application/json");
                x.sendResponseHeaders(200, bytes.length); x.getResponseBody().write(bytes); x.close();
            });
            stub.start();
            String baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort();
            var config = JustBuildConfig.defaults();
            var old = new JustBuildConfig.ModelProfile("API setup", "openai", "old-model");
            old.apiKey = "old-openai-fixture-key";
            config.replaceProfiles(List.of(old), old.name); config.detail = "quick"; config.maxQuestions = 0;
            config.creativeDirection = false; config.guides = false;
            new ConfigStore(file).save(config);
            byte[] beforeEditing = read(file);
            try (var world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksRender();
                context.waitFor(mc -> !ClientState.profiles().isEmpty());
                context.runOnClient(mc -> mc.gui.setScreen(new ProfilesScreen(new JustBuildScreen(), false)));
                context.clickScreenButton("OpenAI-compatible");
                context.runOnClient(mc -> {
                    check(field(mc.gui.screen(), "https://…/v1").getValue().equals(JustBuildConfig.DEFAULT_ANTHROPIC_BASE_URL),
                            "provider switch did not select the Anthropic URL");
                    field(mc.gui.screen(), "Profile name").setValue("Native API");
                    field(mc.gui.screen(), "https://…/v1").setValue(baseUrl);
                    field(mc.gui.screen(), "New provider: retype key").setValue("native-fixture-key");
                    field(mc.gui.screen(), "Maximum output tokens").setValue("16000");
                });
                context.clickScreenButton("Pick…");
                context.waitFor(mc -> hasButton(mc.gui.screen(), "native-model"), 400);
                context.runOnClient(mc -> check(!hasButton(mc.gui.screen(), "(CLI default)"), "API picker offered a blank CLI model"));
                context.clickScreenButton("native-model");
                context.clickScreenButton("Test connection");
                context.waitFor(mc -> checks(mc.gui.screen()) != null, 500);
                context.runOnClient(mc -> check(checks(mc.gui.screen()).stream().allMatch(c -> c.status() == ProfileDiagnostics.Status.PASS),
                        "native connection test failed"));
                check(java.util.Arrays.equals(beforeEditing, read(file)), "testing saved the draft");
                context.clickScreenButton("Back to profiles");
                context.runOnClient(mc -> {
                    mc.gui.screen().resize(320, 240);
                    check(field(mc.gui.screen(), "Maximum output tokens").getValue().equals("16000"), "resize lost max tokens");
                });
                context.takeScreenshot("anthropic-profile-small");
                context.runOnClient(mc -> mc.gui.screen().resize(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()));
                context.takeScreenshot("anthropic-profile");
                context.clickScreenButton("Save");
                context.waitFor(mc -> ClientState.string("provider", "").equals("anthropic"));
                var saved = new ConfigStore(file).load();
                check(saved.active().maxTokens == 16000 && saved.active().model.equals("native-model"), "profile packet lost API settings");
                check(saved.active().apiKey.equals("native-fixture-key"), "native key was not saved");
                check(!Files.readString(file).contains("fixture-key"), "config contains plaintext credentials");
                context.runOnClient(mc -> {
                    check(ClientState.profiles().getFirst().maxTokens() == 16000, "client state lost token limit");
                    mc.gui.setScreen(new ProfilesScreen(new JustBuildScreen(), true));
                    field(mc.gui.screen(), "Maximum output tokens").setValue("12000");
                });
                context.clickScreenButton("Test connection");
                context.waitFor(mc -> checks(mc.gui.screen()) != null, 500);
                context.runOnClient(mc -> check(checks(mc.gui.screen()).stream().allMatch(c -> c.status() == ProfileDiagnostics.Status.PASS),
                        "local native connection test failed"));
                check(bodies.getLast().get("max_tokens").getAsInt() == 12000, "local diagnostics ignored draft token limit");
                check(new ConfigStore(file).load().active().maxTokens == 16000, "local diagnostic saved draft");
                context.clickScreenButton("Back to profiles");
                context.runOnClient(mc -> mc.gui.setScreen(null));

                BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition().above(12));
                world.getServer().runOnServer(server -> {
                    for (BlockPos pos : BlockPos.betweenClosed(at.offset(-3, -1, -3), at.offset(3, 3, 3)))
                        server.overworld().setBlock(pos, pos.getY() == at.getY() - 1 ? Blocks.STONE.defaultBlockState()
                                : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                });
                command(context, "zone 1 " + pos(at.offset(-2, -1, -2)));
                command(context, "zone 2 " + pos(at.offset(2, -1, 2)));
                command(context, "build a simple stone pad");
                context.waitFor(mc -> ClientState.preview() != null, 1200);
                check(world.getServer().computeOnServer(s -> s.overworld().getBlockState(at).isAir()), "plan placed without confirmation");
                command(context, "confirm");
                context.waitFor(mc -> ClientState.preview() == null && ClientState.string("selectedBuild", null) != null);
                context.waitTicks(20);
                check(world.getServer().computeOnServer(s -> s.overworld().getBlockState(at).is(Blocks.STONE)), "native plan was not placed");
                check(keys.stream().allMatch("native-fixture-key"::equals), "provider switch reused the old key");
                check(bodies.size() >= 3 && bodies.stream().allMatch(b -> b.has("system") && b.get("model").getAsString().equals("native-model")),
                        "native protocol or model was lost during planning");
            }
        } catch (Exception e) { throw new AssertionError(e); }
        finally {
            if (stub != null) stub.stop(0);
            try { if (original == null) Files.deleteIfExists(file); else Files.write(file, original); }
            catch (Exception e) { throw new AssertionError(e); }
        }
    }

    private static EditBox field(Screen screen, String hint) { return screen.children().stream().filter(EditBox.class::isInstance)
            .map(EditBox.class::cast).filter(e -> e.getMessage().getString().equals(hint)).findFirst().orElseThrow(); }
    private static boolean hasButton(Screen screen, String text) { return screen != null && screen.children().stream()
            .anyMatch(w -> w instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals(text)); }
    @SuppressWarnings("unchecked") private static List<ProfileDiagnostics.Check> checks(Screen screen) {
        if (!(screen instanceof ProfileTestScreen)) return null;
        try { var field = ProfileTestScreen.class.getDeclaredField("checks"); field.setAccessible(true); return (List<ProfileDiagnostics.Check>) field.get(screen); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static byte[] read(java.nio.file.Path path) { try { return Files.exists(path) ? Files.readAllBytes(path) : null; } catch (Exception e) { throw new AssertionError(e); } }
    private static String pos(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
    private static void command(ClientGameTestContext context, String command) { context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + command)); context.waitTicks(8); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
