package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.JustBuildMod;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Auto-build places fresh AI builds and edits; alternatives, checkpoints, designs, and retries still preview. */
public final class AutoBuildGameTest implements FabricClientGameTest {
    private static final String MARKER = """
            {"kind":"build","title":"Auto marker","summary":"",
             "operations":[{"op":"fill","block":"gold_block","from":[-1,0,0],"to":[1,0,0]}]}
            """;

    @Override
    public void runTest(ClientGameTestContext context) {
        var file = FabricLoader.getInstance().getConfigDir().resolve("justbuild.json");
        byte[] original;
        try { original = Files.exists(file) ? Files.readAllBytes(file) : null; }
        catch (Exception e) { throw new AssertionError(e); }
        HttpServer stub = stub();
        try {
            JustBuildConfig config = JustBuildConfig.defaults();
            var profile = new JustBuildConfig.ModelProfile("Stub", "openai", "stub");
            profile.baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1";
            config.replaceProfiles(List.of(profile), "Stub");
            config.detail = "quick";
            config.creativeDirection = false;
            new ConfigStore(file).save(config);
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                world.getClientLevel().waitForChunksRender();
                BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition().above(10));
                String player = context.computeOnClient(mc -> mc.player.getName().getString());
                world.getServer().runOnServer(server -> {
                    for (BlockPos pos : BlockPos.betweenClosed(at.offset(-6, -1, -6), at.offset(6, 6, 6))) {
                        server.overworld().setBlock(pos, pos.getY() == at.getY() - 1 ? Blocks.STONE.defaultBlockState()
                                : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                });
                world.getServer().runCommand("gamemode creative " + player);
                world.getServer().runCommand("tp " + player + " " + (at.getX() + 0.5) + " " + (at.getY() + 4)
                        + " " + (at.getZ() + 6.5) + " 180 25");
                context.waitTicks(20);
                command(context, "zone 1 " + position(at.offset(-1, -1, 0)));
                command(context, "zone 2 " + position(at.offset(1, -1, 0)));
                check("false".equals(context.computeOnClient(mc -> ClientState.string("autoBuild", "?"))), "Auto-build is on by default");

                command(context, "autobuild on");
                context.waitFor(mc -> "true".equals(ClientState.string("autoBuild", "?")), 100);
                command(context, "build a gold marker");
                for (int i = 0; i < 200 && !gold(world, at); i++) context.waitTicks(2);
                check(gold(world, at), "with Auto-build on, the result was not placed");
                check(context.computeOnClient(mc -> ClientState.preview() == null), "Auto-build left a preview waiting");

                String id = context.computeOnClient(mc -> ClientState.builds().getFirst().id());
                command(context, "select " + id);
                command(context, "checkpoint save Original");
                command(context, "design save Original marker");
                String design = context.computeOnClient(mc -> ClientState.designs().stream()
                        .filter(d -> d.name().equals("Original marker")).findFirst().orElseThrow().id());

                command(context, "edit #" + id + " make the marker diamond");
                awaitBlock(context, world, at, Blocks.DIAMOND_BLOCK);
                check(context.computeOnClient(mc -> ClientState.preview() == null), "Auto-build left an AI edit waiting for preview");
                command(context, "checkpoint restore Original");
                context.waitFor(mc -> ClientState.preview() != null, 1200);
                check(block(world, at, Blocks.DIAMOND_BLOCK), "Auto-build applied a saved checkpoint without confirmation");
                command(context, "cancel");

                command(context, "undo");
                command(context, "undo confirm");
                awaitBlock(context, world, at, Blocks.GOLD_BLOCK);
                command(context, "undo");
                command(context, "undo confirm");
                awaitBlock(context, world, at, Blocks.AIR);
                command(context, "design place " + design);
                context.waitFor(mc -> ClientState.preview() != null, 1200);
                check(block(world, at, Blocks.AIR), "Auto-build placed a saved design without confirmation");
                command(context, "cancel");
                command(context, "retry");
                context.waitFor(mc -> ClientState.preview() != null, 1200);
                check(block(world, at, Blocks.AIR), "Auto-build placed a retried saved plan without confirmation");
                command(context, "cancel");
                command(context, "alternatives build 2 a gold marker");
                context.waitFor(mc -> ClientState.preview() != null && ClientState.preview().options().size() == 2, 1200);
                check(!gold(world, at), "Auto-build placed an alternative before the player chose");
                command(context, "cancel");

                command(context, "autobuild off");
                context.waitFor(mc -> "false".equals(ClientState.string("autoBuild", "?")), 100);
                check(world.getServer().computeOnServer(s -> JustBuildMod.manager() != null), "the server stopped");
            }
        } catch (Exception e) {
            throw new AssertionError("Could not exercise Auto-build", e);
        } finally {
            stub.stop(0);
            try {
                if (original == null) Files.deleteIfExists(file);
                else Files.write(file, original);
            } catch (Exception e) { throw new AssertionError("Could not restore test config", e); }
        }
    }

    private static boolean gold(TestSingleplayerContext world, BlockPos at) { return block(world, at, Blocks.GOLD_BLOCK); }

    private static boolean block(TestSingleplayerContext world, BlockPos at, Block block) {
        return world.getServer().computeOnServer(s -> s.overworld().getBlockState(at).is(block));
    }

    private static void awaitBlock(ClientGameTestContext context, TestSingleplayerContext world, BlockPos at, Block block) {
        for (int i = 0; i < 200 && !block(world, at, block); i++) context.waitTicks(2);
        check(block(world, at, block), "the expected " + block + " was not placed");
    }

    private static HttpServer stub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger calls = new AtomicInteger();
            server.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                JsonObject message = new JsonObject(); message.addProperty("content", calls.getAndIncrement() == 1
                        ? MARKER.replace("gold_block", "diamond_block") : MARKER);
                JsonObject choice = new JsonObject(); choice.add("message", message);
                JsonArray choices = new JsonArray(); choices.add(choice);
                JsonObject reply = new JsonObject(); reply.add("choices", choices);
                byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) { throw new AssertionError("Could not start test AI", e); }
    }

    private static String position(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }
    private static void command(ClientGameTestContext context, String text) {
        context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + text));
        context.waitTicks(10);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
