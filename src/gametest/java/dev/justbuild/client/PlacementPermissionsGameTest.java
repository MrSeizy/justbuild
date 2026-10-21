package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.JustBuildMod;
import dev.justbuild.command.JustBuildCommand;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Real operator revocation while AI, a preview, or an incremental placement holds an old command source. */
public final class PlacementPermissionsGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var file = FabricLoader.getInstance().getConfigDir().resolve("justbuild.json");
        byte[] original;
        try { original = Files.exists(file) ? Files.readAllBytes(file) : null; }
        catch (Exception e) { throw new AssertionError(e); }
        CountDownLatch received = new CountDownLatch(1), release = new CountDownLatch(1);
        HttpServer stub = stub(received, release);
        try {
            var config = JustBuildConfig.defaults();
            var profile = new JustBuildConfig.ModelProfile("Stub", "openai", "stub");
            profile.baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1";
            config.replaceProfiles(List.of(profile), "Stub");
            config.playerAi = "off"; config.detail = "quick"; config.creativeDirection = false;
            config.blocksPerTick = 1; config.previewAbove = 0;
            new ConfigStore(file).save(config);
            try (TestDedicatedServerContext server = context.worldBuilder().createServer();
                    TestDedicatedServerConnection connection = server.connect()) {
                connection.waitForChunksRender();
                String name = context.computeOnClient(mc -> mc.player.getName().getString());
                BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition().above(10));
                server.runCommand("op " + name);
                server.runOnServer(s -> {
                    var source = s.getPlayerList().getPlayers().getFirst().createCommandSourceStack();
                    var m = JustBuildMod.manager();
                    for (BlockPos p : BlockPos.betweenClosed(at.offset(-4, -1, -4), at.offset(14, 5, 4))) {
                        s.overworld().setBlock(p, p.getY() == at.getY() - 1 ? Blocks.STONE.defaultBlockState()
                                : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                    m.zoneCorner(source, 1, at.offset(-1, -1, 0));
                    m.zoneCorner(source, 2, at.offset(1, -1, 0));
                    m.autoBuild(source, "on"); m.start(source, "build a gold marker");
                });
                for (int i = 0; i < 300 && received.getCount() != 0; i++) context.waitTicks(2);
                check(received.getCount() == 0, "the delayed AI fixture did not start");
                server.runCommand("deop " + name);
                release.countDown(); context.waitTicks(40);
                server.runOnServer(s -> check(s.overworld().getBlockState(at).isAir(),
                        "an AI result placed blocks after operator permission was revoked"));

                server.runCommand("op " + name);
                server.runOnServer(s -> {
                    var source = s.getPlayerList().getPlayers().getFirst().createCommandSourceStack();
                    var m = JustBuildMod.manager();
                    m.clearZone(source); m.autoBuild(source, "off");
                    for (int x = 0; x < 12; x++) s.overworld().setBlock(at.east(x), Blocks.GOLD_BLOCK.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    m.partCorner(source, 1, at); m.partCorner(source, 2, at.east(11)); m.addExisting(source, "Permission marker");
                });
                context.waitFor(mc -> !ClientState.builds().isEmpty(), 1200);
                String id = context.computeOnClient(mc -> ClientState.builds().getFirst().id());

                // Confirm with the captured source before the next tick can invalidate the preview.
                server.runOnServer(s -> {
                    var player = s.getPlayerList().getPlayers().getFirst();
                    var source = player.createCommandSourceStack(); var m = JustBuildMod.manager();
                    m.replaceMaterial(source, "gold_block", "diamond_block");
                    check(m.isBusy(id), "the fixture did not create a material preview");
                    s.getPlayerList().deop(player.nameAndId());
                    check(JustBuildCommand.allowed(source) && !JustBuildCommand.allowed(player.createCommandSourceStack()),
                            "the fixture did not retain a stale privileged source");
                    m.confirm(source); m.tick();
                    check(!m.isBusy(id), "revoked preview kept the build busy");
                    for (int x = 0; x < 12; x++) check(s.overworld().getBlockState(at.east(x)).is(Blocks.GOLD_BLOCK),
                            "a stale source confirmed a local edit");
                });

                // Stop midway through an edit, then let the already authorized rollback restore its one changed cell.
                server.runCommand("op " + name);
                server.runOnServer(s -> {
                    var player = s.getPlayerList().getPlayers().getFirst();
                    var source = player.createCommandSourceStack(); var m = JustBuildMod.manager();
                    m.replaceMaterial(source, "gold_block", "diamond_block"); m.confirm(source); m.tick();
                    long changed = java.util.stream.IntStream.range(0, 12)
                            .filter(x -> s.overworld().getBlockState(at.east(x)).is(Blocks.DIAMOND_BLOCK)).count();
                    check(changed == 1, "the partial edit fixture did not place exactly one cell: " + changed);
                    s.getPlayerList().deop(player.nameAndId()); m.tick();
                });
                context.waitTicks(30);
                server.runOnServer(s -> {
                    check(!JustBuildMod.manager().isBusy(id), "revoked edit did not finish rollback");
                    for (int x = 0; x < 12; x++) check(s.overworld().getBlockState(at.east(x)).is(Blocks.GOLD_BLOCK),
                            "revoked edit continued or failed to restore its partial work");
                });
            }
        } catch (Exception e) { throw new AssertionError("Could not exercise live placement permissions", e); }
        finally {
            release.countDown(); stub.stop(0);
            try { if (original == null) Files.deleteIfExists(file); else Files.write(file, original); }
            catch (Exception e) { throw new AssertionError("Could not restore test config", e); }
        }
    }

    private static HttpServer stub(CountDownLatch received, CountDownLatch release) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes(); received.countDown();
                    if (!release.await(30, TimeUnit.SECONDS)) throw new java.io.IOException("Fixture was never released");
                    JsonObject message = new JsonObject();
                    message.addProperty("content", """
                            {"kind":"build","title":"Permission marker","summary":"",
                             "operations":[{"op":"fill","block":"gold_block","from":[-1,0,0],"to":[1,0,0]}]}
                            """);
                    JsonObject choice = new JsonObject(); choice.add("message", message);
                    JsonArray choices = new JsonArray(); choices.add(choice);
                    JsonObject reply = new JsonObject(); reply.add("choices", choices);
                    byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (java.io.IOException ignored) { /* Revocation may close the request before the response. */ }
                finally { exchange.close(); }
            });
            server.start(); return server;
        } catch (Exception e) { throw new AssertionError("Could not start permission fixture", e); }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
