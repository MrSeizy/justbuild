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
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import com.mojang.blaze3d.platform.InputConstants;

/** Building and undoing while the player travels between dimensions, and Nether builds under the bedrock roof. */
public final class DimensionGameTest implements FabricClientGameTest {
    private static final String PLAN = """
            {"kind":"build","title":"Travel marker","summary":"Three gold blocks",
             "operations":[{"op":"fill","block":"gold_block","from":[-1,0,0],"to":[1,0,0]}]}
            """;
    /** A cavern floor in the Nether, with a bedrock roof well above it. */
    private static final BlockPos CAVERN = new BlockPos(8, 64, 8);
    /** Far from spawn, so the Overworld unloads it once the player leaves. */
    private static final BlockPos FAR = new BlockPos(4000, 100, 4000);

    @Override
    public void runTest(ClientGameTestContext context) {
        var file = FabricLoader.getInstance().getConfigDir().resolve("justbuild.json");
        byte[] original;
        try { original = Files.exists(file) ? Files.readAllBytes(file) : null; }
        catch (Exception e) { throw new AssertionError(e); }
        AtomicInteger calls = new AtomicInteger(), reviews = new AtomicInteger();
        Semaphore answers = new Semaphore(0);
        HttpServer stub = stub(calls, reviews, answers);
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
                String player = context.computeOnClient(mc -> mc.player.getName().getString());
                world.getServer().runCommand("gamemode creative " + player);
                prepare(world);

                // A zone on a Nether cavern floor builds on that floor, not on top of the bedrock roof.
                travel(context, world, player, "the_nether", CAVERN.above());
                answers.release();
                command(context, "zone 1 " + position(CAVERN.west()));
                command(context, "zone 2 " + position(CAVERN.east()));
                command(context, "build a three-block marker");
                context.waitFor(mc -> ClientState.preview() != null && FloorPreview.blocks().size() == 3, 1200);
                String at = context.computeOnClient(mc -> ClientState.preview().position());
                check(at.startsWith(CAVERN.above().toShortString()), "the Nether build stood at " + at + ", not on the cavern floor");
                check(reviews.get() == 0, "a draft with no warnings was sent for review");
                command(context, "cancel");
                command(context, "zone clear");

                // A plan that arrives after the player left its dimension is kept for /justbuild retry.
                travel(context, world, player, "overworld", FAR.above());
                command(context, "zone 1 " + position(FAR.west()));
                command(context, "zone 2 " + position(FAR.east()));
                int before = calls.get();
                command(context, "build a marker while I travel");
                context.waitFor(mc -> calls.get() > before, 1200);
                travel(context, world, player, "the_nether", CAVERN.above());
                waitUntilUnloaded(context, world);
                answers.release();
                context.waitFor(mc -> ClientState.string("retainedPlan", null) != null || ClientState.preview() != null, 1200);
                check(!world.getServer().computeOnServer(s -> s.overworld().hasChunk(FAR.getX() >> 4, FAR.getZ() >> 4)),
                        "checking the plan loaded the area the player left");
                check(context.computeOnClient(mc -> ClientState.string("retainedPlan", null) != null),
                        "a plan for an area the player left was not kept for retry");
                check(saw(context, "Return to minecraft:overworld"), "the kept plan did not say where to go back to");
                // The checks report the unloaded area as unknown, which a review cannot fix, so "When needed" skips it.
                check(reviews.get() == 0, "a draft whose only problem is an unloaded area was sent for review");
                travel(context, world, player, "overworld", FAR.above());
                command(context, "retry");
                context.waitFor(mc -> ClientState.preview() != null && FloorPreview.blocks().size() == 3, 1200);
                check(calls.get() == before + 1, "retrying the kept plan called the AI again");
                command(context, "cancel");

                // An earlier alternative must not load chunks after a later option finishes while away.
                int alternativeCalls = calls.get();
                answers.release();
                command(context, "alternatives build 2 a marker while I travel");
                context.waitFor(mc -> calls.get() >= alternativeCalls + 2, 1200);
                travel(context, world, player, "the_nether", CAVERN.above());
                waitUntilUnloaded(context, world);
                answers.release();
                context.waitFor(mc -> ClientState.string("retainedPlan", null) != null || ClientState.preview() != null, 1200);
                check(!world.getServer().computeOnServer(s -> s.overworld().hasChunk(FAR.getX() >> 4, FAR.getZ() >> 4)),
                        "offering an earlier alternative loaded the area the player left");
                check(context.computeOnClient(mc -> ClientState.preview() == null
                                && ClientState.string("retainedPlan", null) != null),
                        "an unloaded alternative was not retained without a preview");
                travel(context, world, player, "overworld", FAR.above());
                command(context, "retry");
                context.waitFor(mc -> ClientState.preview() != null && FloorPreview.blocks().size() == 3, 1200);
                check(calls.get() == alternativeCalls + 2, "retrying the kept alternative called the AI again");
                check(context.computeOnClient(mc -> ClientState.preview().options().isEmpty()),
                        "retrying an unloaded alternative retained stale option controls");
                command(context, "cancel");

                // A preview that arrives while the player is away still shows its ghost blocks when they return.
                world.getServer().runCommand("forceload add " + FAR.getX() + " " + FAR.getZ());
                int previewCalls = calls.get();
                command(context, "build a marker to see on return");
                context.waitFor(mc -> calls.get() > previewCalls, 1200);
                travel(context, world, player, "the_nether", CAVERN.above());
                answers.release();
                context.waitFor(mc -> ClientState.preview() != null, 1200);
                travel(context, world, player, "overworld", FAR.above());
                check(context.computeOnClient(mc -> FloorPreview.blocks().size() == 3), "the ghost model was lost while away");
                check(reviews.get() == 0, "a draft for a force-loaded area was sent for review");
                command(context, "confirm");
                context.waitFor(mc -> ClientState.preview() == null, 1200);
                context.waitTicks(20);
                check(world.getServer().computeOnServer(s -> s.overworld().getBlockState(FAR.above()).is(Blocks.GOLD_BLOCK)),
                        "the confirmed preview did not build");
                world.getServer().runCommand("forceload remove " + FAR.getX() + " " + FAR.getZ());

                // Travelling refreshes the panel, which names the dimension of builds left behind.
                travel(context, world, player, "the_nether", CAVERN.above());
                check(context.computeOnClient(mc -> "minecraft:overworld".equals(ClientState.builds().getFirst().elsewhere())),
                        "the panel did not mark the Overworld build after travelling");
                context.getInput().pressKey(InputConstants.KEY_RSHIFT);
                context.waitForScreen(JustBuildScreen.class);
                context.clickScreenButton("History");
                context.waitTicks(5);
                context.takeScreenshot("history-build-elsewhere");
                context.getInput().pressKey(InputConstants.KEY_ESCAPE);
                context.waitTicks(10);

                // Undo from another dimension says where the build is, even with a zone selected here.
                int builds = savedBuilds(world);
                command(context, "zone 1 " + position(CAVERN.west()));
                command(context, "zone 2 " + position(CAVERN.east()));
                command(context, "undo");
                check(saw(context, "Travel there to undo it"), "undo did not say which dimension the build is in");
                check(!saw(context, "Your build zone is in another dimension"), "undo blamed the zone selected here");
                check(savedBuilds(world) == builds, "undo from another dimension changed the build");
                command(context, "zone clear");
                travel(context, world, player, "overworld", FAR.above());
                command(context, "undo");
                command(context, "undo confirm");
                for (int i = 0; i < 240 && savedBuilds(world) >= builds; i++) context.waitTicks(5);
                check(savedBuilds(world) < builds, "undo did not finish");
                context.waitTicks(20);
                check(world.getServer().computeOnServer(s -> s.overworld().getBlockState(FAR.above()).isAir()),
                        "undo did not remove the build");
            }
        } catch (Exception e) {
            throw new AssertionError("Could not exercise builds across dimensions", e);
        } finally {
            // A failed check can leave a call waiting for its answer, and stop() waits for every call to finish.
            answers.release(1000);
            stub.stop(0);
            try {
                if (original == null) Files.deleteIfExists(file);
                else Files.write(file, original);
            } catch (Exception e) { throw new AssertionError("Could not restore test config", e); }
        }
    }

    /** Stone floors with open air above at both sites, and a bedrock roof over the cavern. */
    private static void prepare(TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            var nether = server.getLevel(Level.NETHER);
            for (BlockPos pos : BlockPos.betweenClosed(CAVERN.offset(-4, 0, -4), CAVERN.offset(4, 30, 4))) {
                int dy = pos.getY() - CAVERN.getY();
                nether.setBlock(pos, dy == 0 ? Blocks.STONE.defaultBlockState() : dy == 30 ? Blocks.BEDROCK.defaultBlockState()
                        : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
            for (BlockPos pos : BlockPos.betweenClosed(FAR.offset(-4, 0, -4), FAR.offset(4, 12, 4))) {
                server.overworld().setBlock(pos, pos.getY() == FAR.getY() ? Blocks.STONE.defaultBlockState()
                        : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        });
    }

    private static void travel(ClientGameTestContext context, TestSingleplayerContext world, String player, String dimension,
            BlockPos to) {
        world.getServer().runCommand("execute in minecraft:" + dimension + " run tp " + player + " " + (to.getX() + 0.5)
                + " " + to.getY() + " " + (to.getZ() + 4.5) + " 180 30");
        context.waitFor(mc -> mc.level != null && mc.level.dimension().identifier().getPath().equals(dimension), 1200);
        world.getConnection().waitForChunksRender();
        context.waitTicks(20);
    }

    private static void waitUntilUnloaded(ClientGameTestContext context, TestSingleplayerContext world) {
        for (int i = 0; i < 120 && world.getServer().computeOnServer(s -> s.overworld().hasChunk(FAR.getX() >> 4, FAR.getZ() >> 4)); i++) {
            context.waitTicks(5);
        }
        check(!world.getServer().computeOnServer(s -> s.overworld().hasChunk(FAR.getX() >> 4, FAR.getZ() >> 4)),
                "the Overworld build area stayed loaded after the player left");
    }

    private static int savedBuilds(TestSingleplayerContext world) {
        return world.getServer().computeOnServer(s -> JustBuildMod.manager().savedBuildCount());
    }

    private static boolean saw(ClientGameTestContext context, String text) {
        return context.computeOnClient(mc -> ClientState.lines().stream().anyMatch(line -> line.getString().contains(text)));
    }

    /** Answers each AI call with the plan once the test releases it, so the player can travel while it waits. */
    private static HttpServer stub(AtomicInteger calls, AtomicInteger reviews, Semaphore answers) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                // Only plan calls wait for the test; a review of the draft is answered at once.
                String review = StubReview.draft(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (review != null) reviews.incrementAndGet();
                else {
                    calls.incrementAndGet();
                    try { answers.acquire(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                JsonObject message = new JsonObject(); message.addProperty("content", review != null ? review : PLAN);
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
