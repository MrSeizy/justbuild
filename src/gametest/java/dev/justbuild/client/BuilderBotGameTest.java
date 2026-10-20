package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.JustBuildMod;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import dev.justbuild.net.BuilderBotsPayload;
import dev.justbuild.world.BuildRegion;
import dev.justbuild.world.BuilderWorksite;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Semaphore;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Real server activity, textured rendering, gait, idle previews, cancellation, expiry, and resource reload. */
public final class BuilderBotGameTest implements FabricClientGameTest {
    private static final BlockPos AT = new BlockPos(0, 100, 0);
    private static final String PLAN = """
            {"kind":"build","title":"Bot worksite","summary":"A small platform",
             "operations":[{"op":"fill","block":"gold_block","from":[-2,0,-2],"to":[2,0,2]}]}
            """;

    @Override public void runTest(ClientGameTestContext context) {
        var file = FabricLoader.getInstance().getConfigDir().resolve("justbuild.json");
        byte[] original;
        try { original = Files.exists(file) ? Files.readAllBytes(file) : null; }
        catch (Exception e) { throw new AssertionError(e); }
        Semaphore answers = new Semaphore(0);
        HttpServer stub = stub(answers);
        boolean originalHideGui = context.computeOnClient(mc -> mc.options.hideGui);
        try {
            var config = JustBuildConfig.defaults();
            var profile = new JustBuildConfig.ModelProfile("Stub", "openai", "stub");
            profile.baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1";
            config.replaceProfiles(List.of(profile), "Stub");
            config.detail = "quick"; config.creativeDirection = false; config.previewAbove = 0; config.blocksPerTick = 1;
            new ConfigStore(file).save(config);
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                world.getClientLevel().waitForChunksRender();
                String player = context.computeOnClient(mc -> mc.player.getName().getString());
                String id = context.computeOnClient(mc -> mc.player.getUUID().toString());
                world.getServer().runOnServer(server -> {
                    for (BlockPos p : BlockPos.betweenClosed(AT.offset(-16, -1, -16), AT.offset(16, 6, 16))) {
                        server.overworld().setBlock(p, p.getY() == 99 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                });
                world.getServer().runCommand("gamemode spectator " + player);
                world.getServer().runCommand("time set day");
                world.getServer().runCommand("tp " + player + " 5 103 -7 35 20");
                context.runOnClient(mc -> mc.options.hideGui = true);
                context.waitTicks(15);
                command(context, "zone 1 -3 99 -3"); command(context, "zone 2 3 99 3");
                command(context, "build a small platform");
                context.waitFor(mc -> BuilderBots.working(id) && BuilderBots.position(id) != null, 600);
                faceBot(context, world, player, id);
                context.waitFor(mc -> BuilderBots.renderedCount() == 1 && BuilderBots.hammering(id), 600);
                for (int frame = 0; frame < 6; frame++) {
                    context.takeScreenshot("builder-bot-swing-" + frame);
                    context.waitTicks(4);
                }
                check(world.getServer().computeOnServer(s -> s.overworld().getBlockState(AT).isAir()
                                && JustBuildMod.manager().savedBuildCount() == 0), "the cosmetic worker changed the world during planning");
                Vec3 before = context.computeOnClient(mc -> BuilderBots.position(id));
                context.waitFor(mc -> BuilderBots.walking(id) && BuilderBots.position(id).distanceToSqr(before) > 1, 600);
                faceBot(context, world, player, id);
                context.takeScreenshot("builder-bot-walking");

                var reload = context.computeOnClient(mc -> mc.reloadResourcePacks());
                context.waitFor(mc -> reload.isDone(), 1200); reload.join();
                context.waitFor(mc -> BuilderBots.renderedCount() == 1, 600);
                answers.release();
                context.waitFor(mc -> ClientState.preview() != null && BuilderBots.count() == 1 && !BuilderBots.working(id), 1200);
                context.waitTicks(5);
                check(!context.computeOnClient(mc -> BuilderBots.hammering(id)), "the worker kept hammering while confirmation was pending");
                faceBot(context, world, player, id);
                context.takeScreenshot("builder-bot-waiting");
                command(context, "confirm");
                context.waitFor(mc -> BuilderBots.working(id), 100);
                context.waitFor(mc -> BuilderBots.count() == 0 && ClientState.placing() == null, 600);
                check(world.getServer().computeOnServer(s -> s.overworld().getBlockState(AT).is(Blocks.GOLD_BLOCK)), "placement did not finish");

                command(context, "build another platform");
                context.waitFor(mc -> BuilderBots.working(id), 600);
                command(context, "cancel"); answers.release();
                context.waitFor(mc -> BuilderBots.count() == 0, 200);
                context.runOnClient(mc -> BuilderBots.receive(new BuilderBotsPayload(List.of(new BuilderWorksite("elsewhere",
                        new BuildRegion(Level.NETHER, AT, AT), true)))));
                check(context.computeOnClient(mc -> BuilderBots.count() == 0), "a worker leaked across dimensions");
                context.runOnClient(mc -> BuilderBots.receive(new BuilderBotsPayload(List.of(new BuilderWorksite("stale",
                        new BuildRegion(Level.OVERWORLD, AT, AT), true)))));
                context.waitTicks(105);
                check(context.computeOnClient(mc -> BuilderBots.count() == 0), "a stale worker never expired");
            }
            context.waitTicks(2);
            check(context.computeOnClient(mc -> BuilderBots.count() == 0), "disconnect retained a worker");
        } catch (Exception e) { throw new AssertionError("Could not exercise the builder bot", e); }
        finally {
            context.runOnClient(mc -> mc.options.hideGui = originalHideGui);
            answers.release(100); stub.stop(0);
            try { if (original == null) Files.deleteIfExists(file); else Files.write(file, original); }
            catch (Exception e) { throw new AssertionError("Could not restore bot test config", e); }
        }
    }

    private static void faceBot(ClientGameTestContext context, TestSingleplayerContext world, String player, String id) {
        Vec3 bot = context.computeOnClient(mc -> BuilderBots.position(id));
        Vec3 forward = new Vec3(.5 - bot.x, 0, .5 - bot.z).normalize();
        Vec3 camera = bot.add(forward.scale(2.8)).add(forward.z * 1.1, 1.7, -forward.x * 1.1);
        Vec3 delta = bot.add(0, .8, 0).subtract(camera);
        double yaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
        double pitch = -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        double eyeHeight = context.computeOnClient(mc -> (double) mc.player.getEyeHeight());
        world.getServer().runCommand("tp " + player + " " + camera.x + " " + (camera.y - eyeHeight) + " " + camera.z + " " + yaw + " " + pitch);
        context.waitTicks(5);
    }
    private static HttpServer stub(Semaphore answers) {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                try { answers.acquire(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                var message = new JsonObject(); message.addProperty("content", PLAN);
                var choice = new JsonObject(); choice.add("message", message);
                var choices = new JsonArray(); choices.add(choice);
                var reply = new JsonObject(); reply.add("choices", choices);
                byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            });
            server.start(); return server;
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static void command(ClientGameTestContext context, String command) {
        context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + command)); context.waitTicks(10);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
