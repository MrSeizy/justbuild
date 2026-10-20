package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Doorway approaches must leave both facades before turning; connectivity alone is insufficient. */
public final class ConnectionPathGameTest implements FabricClientGameTest {
    // Optional manual AI smoke test; normal automated runs remain deterministic and make no external calls.
    private final boolean live = "1".equals(System.getenv("JUSTBUILD_LIVE_CONNECTION"));
    private final AtomicReference<String> prompt = new AtomicReference<>("");
    private final AtomicInteger calls = new AtomicInteger();

    @Override public void runTest(ClientGameTestContext c) {
        rejectPreviousSideEntry();
        HttpServer stub = live ? null : stub();
        ConfigStore store = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("justbuild.json"));
        try {
            JustBuildConfig config = JustBuildConfig.defaults();
            if (live) config.activate("Codex");
            else {
                var profile = new JustBuildConfig.ModelProfile("Connection test", "openai", "stub");
                profile.baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1";
                config.replaceProfiles(List.of(profile), profile.name);
            }
            config.detail = live ? "detailed" : "quick";
            config.creativeDirection = false; config.previewAbove = 0; config.maxQuestions = 2;
            store.save(config);
            try (TestSingleplayerContext world = c.worldBuilder().create()) {
                world.getClientLevel().waitForChunksRender();
                BlockPos center = c.computeOnClient(mc -> mc.player.blockPosition().above(18));
                BlockPos a = center.offset(-14, 0, -5), b = center.offset(14, 0, 5);
                String player = c.computeOnClient(mc -> mc.player.getName().getString());
                Map<BlockPos, BlockState> homes = new LinkedHashMap<>();
                cottage(homes, a, Blocks.OAK_PLANKS.defaultBlockState());
                cottage(homes, b, Blocks.BIRCH_PLANKS.defaultBlockState());
                world.getServer().runCommand("gamemode spectator " + player);
                world.getServer().runCommand("tp " + player + " " + pos(center.offset(0, 24, 38)) + " 180 34");
                world.getServer().runCommand("time set day");
                world.getServer().runCommand("weather clear");
                world.getServer().runOnServer(server -> {
                    for (BlockPos p : BlockPos.betweenClosed(center.offset(-36, -1, -27), center.offset(36, 12, 27)))
                        server.overworld().setBlock(p, p.getY() == center.getY() - 1
                                ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    homes.forEach((p, state) -> server.overworld().setBlock(p, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
                });
                c.waitTicks(15);
                adopt(c, a, "Oak cottage"); String first = selected(c);
                command(c, "new"); adopt(c, b, "Birch cottage"); String second = selected(c);
                command(c, "link a " + first); command(c, "link b " + second);
                command(c, "connect a three-block-wide stone brick footpath from the front door of the oak cottage "
                        + "to the front door of the birch cottage, with gentle bends and clear entrance landings");
                c.waitFor(mc -> ClientState.zone() != null && ClientState.zone().minX() == a.getX() - 16);
                var zone = c.computeOnClient(mc -> ClientState.zone());
                check(zone.maxX() == b.getX() + 16 && zone.minZ() == a.getZ() - 16 && zone.maxZ() == b.getZ() + 16,
                        "connection zone did not leave twelve blocks around both cottages");
                // The real AI may use both closer inspection calls and a review round.
                for (int i = 0; i < (live ? 360 : 20) && !c.computeOnClient(mc -> ClientState.preview() != null); i++)
                    c.waitTicks(20);
                check(c.computeOnClient(mc -> ClientState.preview() != null), "no connection preview: "
                        + c.computeOnClient(mc -> ClientState.lines().stream().map(line -> line.getString()).toList()));
                if (!live) {
                    check(prompt.get().contains("actual usable entrances"), "connection prompt lost entrance guidance");
                    check(prompt.get().contains("oak_door"), "live inspection did not expose cottage doorways");
                }
                screenshot(c, live ? "connection-cottages-live-preview" : "connection-cottages-preview");
                command(c, "confirm");
                c.waitFor(mc -> ClientState.preview() == null && ClientState.string("selectedBuild", null) != null);
                c.waitTicks(20);
                world.getServer().runOnServer(server -> {
                    homes.forEach((p, state) -> check(server.overworld().getBlockState(p).equals(state), "connector changed a cottage at " + p));
                    // Only path blocks count: merely walking across untouched grass cannot pass this check.
                    BlockPos start = a.offset(2, -1, 5), end = b.offset(2, -1, 5);
                    Set<BlockPos> path = new HashSet<>();
                    for (BlockPos p : BlockPos.betweenClosed(center.offset(-30, -1, -21), center.offset(30, -1, 21))) {
                        var state = server.overworld().getBlockState(p);
                        if (!state.isAir() && !state.is(Blocks.GRASS_BLOCK) && !homes.containsKey(p)
                                && server.overworld().getBlockState(p.above()).isAir()
                                && server.overworld().getBlockState(p.above(2)).isAir()) path.add(p.immutable());
                    }
                    check(connected(path, start, end), "the path did not continuously reach both front-door landings");
                    checkApproach(path, start, Direction.SOUTH, "oak cottage");
                    checkApproach(path, end, Direction.SOUTH, "birch cottage");
                    if (!live) check(path.contains(center.offset(0, -1, 16)), "route beyond the old four-block margin was clipped");
                });
                command(c, "zone clear"); screenshot(c, live ? "connection-cottages-live-built" : "connection-cottages-built");
                command(c, "undo"); command(c, "undo confirm"); c.waitTicks(20);
                world.getServer().runOnServer(server -> {
                    homes.forEach((p, state) -> check(server.overworld().getBlockState(p).equals(state), "connector undo changed a cottage"));
                    check(server.overworld().getBlockState(a.offset(2, -1, 5)).is(Blocks.GRASS_BLOCK), "undo left the connector behind");
                });
            }
        } catch (dev.justbuild.config.ConfigException e) { throw new AssertionError("Could not configure connection test", e); }
        finally {
            if (stub != null) stub.stop(0);
            try { store.save(JustBuildConfig.defaults()); }
            catch (dev.justbuild.config.ConfigException e) { throw new AssertionError("Could not reset connection test config", e); }
        }
    }

    private static void cottage(Map<BlockPos, BlockState> blocks, BlockPos at, BlockState wall) {
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            blocks.put(at.offset(x, -1, z), Blocks.STONE_BRICKS.defaultBlockState());
            for (int y = 0; y <= 3; y++) if (Math.abs(x) == 4 || Math.abs(z) == 4) {
                boolean post = Math.abs(x) == 4 && Math.abs(z) == 4;
                boolean window = y == 1 && (Math.abs(x) == 4 && Math.abs(z) <= 1 || z == -4 && Math.abs(x) <= 1);
                blocks.put(at.offset(x, y, z), post ? Blocks.OAK_LOG.defaultBlockState()
                        : window ? Blocks.GLASS_PANE.defaultBlockState() : wall);
            }
        }
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++)
            blocks.put(at.offset(x, 4 + (4 - Math.abs(x)) / 2, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
        var door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        blocks.put(at.offset(2, 0, 4), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        blocks.put(at.offset(2, 1, 4), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static boolean connected(Set<BlockPos> path, BlockPos start, BlockPos end) {
        if (!path.contains(start) || !path.contains(end)) return false;
        Set<BlockPos> seen = new HashSet<>(); ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(start); queue.add(start);
        while (!queue.isEmpty()) {
            BlockPos at = queue.removeFirst(); if (at.equals(end)) return true;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos next = at.relative(d); if (path.contains(next) && seen.add(next)) queue.addLast(next);
            }
        }
        return false;
    }

    private static void checkApproach(Set<BlockPos> path, BlockPos first, Direction outward, String building) {
        Direction side = outward.getClockWise();
        for (int step = 0; step < 5; step++) {
            BlockPos center = first.relative(outward, step);
            for (int across = -1; across <= 1; across++) check(path.contains(center.relative(side, across)),
                    building + " needs a full-width straight approach for five blocks before turning; missing "
                            + center.relative(side, across));
            // One edging block is allowed. A sideways branch along the facade is not an outward approach.
            if (step < 4) for (int across : new int[] {-3, 3}) check(!path.contains(center.relative(side, across)),
                    building + " turns alongside the facade before leaving it at " + center.relative(side, across));
        }
    }

    private static void rejectPreviousSideEntry() {
        try (var input = ConnectionPathGameTest.class.getResourceAsStream("/fixtures/side-entry-cottage-path.json")) {
            check(input != null, "missing side-entry regression fixture");
            var response = (dev.justbuild.plan.PlanResponse.Build) dev.justbuild.plan.PlanParser.parse(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8));
            Set<BlockPos> path = new HashSet<>();
            dev.justbuild.plan.Rasterizer.rasterize(response.plan().operations(), 10_000).forEach((cell, block) ->
                    path.add(new BlockPos(cell.x(), cell.y(), cell.z())));
            BlockPos a = new BlockPos(-12, -1, 0), b = new BlockPos(16, -1, 10);
            check(connected(path, a, b), "previous result should pass the old connectivity-only check");
            for (BlockPos first : List.of(a, b)) {
                boolean rejected = false;
                try { checkApproach(path, first, Direction.SOUTH, "previous result"); }
                catch (AssertionError expected) { rejected = true; }
                check(rejected, "approach check accepted the previous facade-hugging path");
            }
        } catch (java.io.IOException | dev.justbuild.plan.PlanException e) {
            throw new AssertionError("Could not read side-entry regression fixture", e);
        }
    }

    private static void adopt(ClientGameTestContext c, BlockPos at, String name) {
        command(c, "box 1 " + pos(at.offset(-4, -1, -4)));
        command(c, "box 2 " + pos(at.offset(4, 6, 4)));
        command(c, "add " + name); c.waitFor(mc -> ClientState.string("selectedBuild", null) != null);
    }
    private static String selected(ClientGameTestContext c) { return c.computeOnClient(mc -> ClientState.string("selectedBuild", null)); }
    private static String pos(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
    private static void command(ClientGameTestContext c, String text) {
        c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + text)); c.waitTicks(8);
    }
    private static void screenshot(ClientGameTestContext c, String name) {
        c.runOnClient(mc -> mc.options.hideGui = true); c.waitTicks(3); c.takeScreenshot(name);
        c.runOnClient(mc -> mc.options.hideGui = false);
    }
    private static void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); }

    private HttpServer stub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                prompt.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                // This bend extends seven blocks past B; the previous four-block margin rejected it.
                String plan = """
                        {"kind":"build","title":"Cottage lane","summary":"Door-to-door footpath","operations":[
                          {"op":"fill","block":"stone_bricks","from":[-13,-1,0],"to":[-11,-1,16],"hollow":false},
                          {"op":"fill","block":"stone_bricks","from":[-11,-1,15],"to":[15,-1,17],"hollow":false},
                          {"op":"fill","block":"stone_bricks","from":[15,-1,10],"to":[17,-1,17],"hollow":false},
                          {"op":"set","block":"gold_block","from":[-14,0,-9]}]}
                        """;
                // The wide overview can skip door blocks. Read each cottage at block resolution first.
                String response = switch (calls.incrementAndGet()) {
                    case 1 -> "{\"kind\":\"inspect\",\"inspection\":{\"from\":[-19,-2,-10],\"to\":[-9,4,0]}}";
                    case 2 -> "{\"kind\":\"inspect\",\"inspection\":{\"from\":[9,-2,0],\"to\":[19,4,10]}}";
                    default -> plan;
                };
                JsonObject message = new JsonObject(); message.addProperty("content", response);
                JsonObject choice = new JsonObject(); choice.add("message", message);
                JsonArray choices = new JsonArray(); choices.add(choice); JsonObject reply = new JsonObject(); reply.add("choices", choices);
                byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start(); return server;
        } catch (java.io.IOException e) { throw new AssertionError("Could not start connection test provider", e); }
    }
}
