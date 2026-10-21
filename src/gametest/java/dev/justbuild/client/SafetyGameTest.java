package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import dev.justbuild.JustBuildMod;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Plans that would act beyond the build, or take forever to draw, are cut down or refused; the server keeps going. */
public final class SafetyGameTest implements FabricClientGameTest {
    private static final String HAZARDS = """
            {"kind":"build","title":"Hazards","summary":"",
             "operations":[{"op":"fill","block":"stone","from":[-3,0,-3],"to":[3,0,3]},
               {"op":"set","block":"piston","from":[3,1,0]},{"op":"set","block":"redstone_block","from":[2,1,0]},
               {"op":"set","block":"sand","from":[0,5,0]},{"op":"set","block":"water","from":[0,6,2]}]}
            """;
    private static final String SLOW = """
            {"kind":"build","title":"Slow","summary":"",
             "operations":[{"op":"replace","block":"stone","target":"diamond_block","from":[0,0,0],"to":[95,95,95],
               "repeat":{"count":256,"step":[0,0,0]},"mirror":{"axis":"x","at":47.5}}]}
            """;
    private static final String HUGE = """
            {"kind":"build","title":"Huge","summary":"",
             "operations":[{"op":"sphere","block":"stone","from":[0,0,0],"radius":1e300,"style":"dome"}]}
            """;
    private static final String MARKER = """
            {"kind":"build","title":"Marker","summary":"",
             "operations":[{"op":"fill","block":"gold_block","from":[-1,0,0],"to":[1,0,0]}]}
            """;

    @Override
    public void runTest(ClientGameTestContext context) {
        var file = FabricLoader.getInstance().getConfigDir().resolve("justbuild.json");
        byte[] original;
        try { original = Files.exists(file) ? Files.readAllBytes(file) : null; }
        catch (Exception e) { throw new AssertionError(e); }
        ConcurrentLinkedQueue<String> replies = new ConcurrentLinkedQueue<>(List.of(HAZARDS, SLOW, HUGE, MARKER));
        HttpServer stub = stub(replies);
        try {
            JustBuildConfig config = JustBuildConfig.defaults();
            var profile = new JustBuildConfig.ModelProfile("Stub", "openai", "stub");
            profile.baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1";
            config.replaceProfiles(List.of(profile), "Stub");
            config.previewAbove = 0;
            config.detail = "quick";
            config.creativeDirection = false;
            new ConfigStore(file).save(config);
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksRender();
                BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition().above(10));
                String player = context.computeOnClient(mc -> mc.player.getName().getString());
                world.getServer().runOnServer(server -> {
                    for (BlockPos pos : BlockPos.betweenClosed(at.offset(-12, -1, -12), at.offset(12, 12, 12))) {
                        server.overworld().setBlock(pos, pos.getY() == at.getY() - 1 ? Blocks.STONE.defaultBlockState()
                                : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                });
                world.getServer().runCommand("gamemode creative " + player);
                world.getServer().runCommand("tp " + player + " " + (at.getX() + 0.5) + " " + (at.getY() + 4)
                        + " " + (at.getZ() + 8.5) + " 180 25");
                context.waitTicks(20);
                command(context, "zone 1 " + position(at.offset(-3, -1, -3)));
                command(context, "zone 2 " + position(at.offset(3, -1, 3)));

                // Pistons, redstone, floating sand, and a spout that would flood past the zone are left out.
                command(context, "build hazards");
                context.waitFor(mc -> ClientState.preview() != null, 1200);
                context.waitTicks(10);
                check(context.computeOnClient(mc -> FloorPreview.blocks().size() == 49
                        && FloorPreview.blocks().values().stream().allMatch(s -> s.is(Blocks.STONE))),
                        "the preview kept a hazard: " + context.computeOnClient(mc -> FloorPreview.blocks().values()
                                .stream().map(s -> s.getBlock().toString()).distinct().toList()));
                check(saw(context, "Left out:"), "the player was not told what was left out");
                command(context, "cancel");

                // A plan that would take minutes to draw is refused on the AI worker; the server keeps ticking.
                int before = world.getServer().computeOnServer(s -> s.getTickCount());
                command(context, "build slow");
                for (int i = 0; i < 300 && !saw(context, "far too much space"); i++) context.waitTicks(2);
                check(saw(context, "far too much space"), "the slow plan was not refused");
                check(world.getServer().computeOnServer(s -> s.getTickCount()) > before, "the server stopped ticking");

                // An absurd radius is refused before anything is drawn, and the workers stay free for the next build.
                command(context, "build huge");
                for (int i = 0; i < 300 && !saw(context, "exceeds the size limit"); i++) context.waitTicks(2);
                check(saw(context, "exceeds the size limit"), "the absurd radius was not refused");
                command(context, "build marker");
                context.waitFor(mc -> ClientState.preview() != null && FloorPreview.blocks().size() == 3, 1200);
                command(context, "confirm");
                context.waitFor(mc -> !ClientState.builds().isEmpty() && ClientState.preview() == null, 1200);
                String id = context.computeOnClient(mc -> ClientState.builds().getFirst().id());
                command(context, "select " + id);

                // Local material swaps use the same hazard filter as AI plans.
                world.getServer().runOnServer(s -> JustBuildMod.manager().replaceMaterial(
                        s.getPlayerList().getPlayers().getFirst().createCommandSourceStack(), "gold_block", "piston"));
                context.waitTicks(10);
                check(context.computeOnClient(mc -> ClientState.preview() == null), "local materials offered a piston preview");
                check(world.getServer().computeOnServer(s -> s.overworld().getBlockState(at).is(Blocks.GOLD_BLOCK)),
                        "a local material swap placed a piston");

                // A supported sand preview becomes unsafe if its support is removed before confirmation.
                world.getServer().runOnServer(s -> JustBuildMod.manager().replaceMaterial(
                        s.getPlayerList().getPlayers().getFirst().createCommandSourceStack(), "gold_block", "sand"));
                context.waitFor(mc -> ClientState.preview() != null, 1200);
                world.getServer().runOnServer(s -> {
                    s.overworld().setBlock(at.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    JustBuildMod.manager().confirm(s.getPlayerList().getPlayers().getFirst().createCommandSourceStack());
                    JustBuildMod.manager().tick();
                });
                check(world.getServer().computeOnServer(s -> s.overworld().getBlockState(at).is(Blocks.GOLD_BLOCK)),
                        "confirmation placed sand after its support disappeared");
                context.waitTicks(10);
                check(saw(context, "no longer safe"), "changed-world safety refusal was not explained");

                // Repairing an adopted hazard must not reintroduce it into the world.
                world.getServer().runOnServer(s -> {
                    var m = JustBuildMod.manager();
                    var source = s.getPlayerList().getPlayers().getFirst().createCommandSourceStack();
                    m.cancel(source); m.deselectBuild(source); m.clearZone(source);
                    BlockPos hazard = at.offset(5, 0, 0);
                    s.overworld().setBlock(hazard, Blocks.PISTON.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    m.partCorner(source, 1, hazard); m.partCorner(source, 2, hazard); m.addExisting(source, "Existing piston");
                    s.overworld().setBlock(hazard, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    m.repair(source, true, null);
                    m.confirm(source); m.tick();
                    check(s.overworld().getBlockState(hazard).isAir(), "repair restored an unsafe piston");
                });

                // With no explicit zone, a one-cell water edit may flow inside the rest of its saved basin.
                world.getServer().runOnServer(s -> {
                    var m = JustBuildMod.manager();
                    var source = s.getPlayerList().getPlayers().getFirst().createCommandSourceStack();
                    m.deselectBuild(source); m.clearZone(source);
                    BlockPos basin = at.offset(6, 0, 5);
                    for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                        s.overworld().setBlock(basin.offset(x, -1, z), Blocks.STONE.defaultBlockState(),
                                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        s.overworld().setBlock(basin.offset(x, 0, z), (Math.abs(x) == 2 || Math.abs(z) == 2
                                ? Blocks.STONE : x == 0 && z == 0 ? Blocks.GOLD_BLOCK : Blocks.AIR).defaultBlockState(),
                                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                    m.partCorner(source, 1, basin.offset(-2, -1, -2)); m.partCorner(source, 2, basin.offset(2, 0, 2));
                    m.addExisting(source, "Contained basin"); m.clearPart(source); m.clearZone(source);
                    m.replaceMaterial(source, "gold_block", "water"); m.confirm(source); m.tick();
                    check(s.overworld().getBlockState(basin).is(Blocks.WATER),
                            "safe water inside the unchanged saved footprint was refused");
                });
            }
        } catch (Exception e) {
            throw new AssertionError("Could not exercise placement safety", e);
        } finally {
            stub.stop(0);
            try {
                if (original == null) Files.deleteIfExists(file);
                else Files.write(file, original);
            } catch (Exception e) { throw new AssertionError("Could not restore test config", e); }
        }
    }

    private static boolean saw(ClientGameTestContext context, String text) {
        return context.computeOnClient(mc -> ClientState.lines().stream().anyMatch(line -> line.getString().contains(text)));
    }

    private static HttpServer stub(ConcurrentLinkedQueue<String> replies) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                String review = StubReview.draft(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String plan = review != null ? review : replies.poll();
                JsonObject message = new JsonObject(); message.addProperty("content", plan == null ? MARKER : plan);
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
