package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import dev.justbuild.world.RegionStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import com.mojang.blaze3d.platform.InputConstants;

/** Move previews, edit only a floor, protect a manual detail, exchange designs, connect builds, and choose options. */
public final class AdvancedToolsGameTest implements FabricClientGameTest {
    private final Queue<String> replies = new ConcurrentLinkedQueue<>();
    private final Queue<String> prompts = new ConcurrentLinkedQueue<>();
    private final AtomicInteger calls = new AtomicInteger();
    private static final String ORIGINAL = plan(fill("oak_planks"), set("oak_stairs[facing=north]", 0, 1, 0));
    private static final String REVISION = plan(fill("stone"), set("stone_brick_stairs[facing=north]", 0, 1, 0));

    @Override public void runTest(ClientGameTestContext context) {
        HttpServer stub = stub();
        ConfigStore config = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("justbuild.json"));
        configure(config, stub.getAddress().getPort());
        RelaySeen.clear();
        RelaySeen.command("justbuild alternatives build 3 a small cabin");
        for (int i = 1; i <= 3; i++) check(RelaySeen.snapshot().requests().contains(
                dev.justbuild.prompt.AlternativeRequests.variant("a small cabin", i, 3)), "the relay did not recognize an authorized alternative");
        RelaySeen.command("justbuild connect a stone path");
        check(RelaySeen.snapshot().requests().contains("a stone path"), "the relay did not recognize a connection request");
        RelaySeen.clear();
        String exported;
        try {
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksRender();
                BlockPos a = context.computeOnClient(mc -> mc.player.blockPosition().offset(0, 18, 10));
                BlockPos center = a.east(), b = a.east(20), d = a.east(40);
                String player = context.computeOnClient(mc -> mc.player.getName().getString());
                platform(world, a, 52);
                camera(context, world, player, a);
                zone(context, a, 7);
                replies.add(ORIGINAL);
                command(context, "build a small advanced test workshop");
                awaitCalls(context, 1);
                awaitPreview(context);
                // The in-world controls send these commands (PreviewControlsGameTest clicks the controls themselves).
                command(context, "preview move 1 0 0"); command(context, "preview move 0 1 0");
                command(context, "preview rotate 90"); command(context, "preview snap");
                openCreate(context);
                check(context.computeOnClient(mc -> ClientState.preview().position().startsWith(center.toShortString())), "preview did not move and snap");
                check(state(world, center).isAir(), "moving the preview changed the world");
                context.takeScreenshot("advanced-preview-controls");
                int warnings = context.computeOnClient(mc -> ClientState.preview().quality().size());
                check(warnings > 0, "the deliberately unfinished staircase was not flagged");
                context.clickScreenButton("Checks (" + warnings + ")");
                context.waitForScreen(QualityScreen.class); context.takeScreenshot("advanced-quality-checks");
                context.clickScreenButton("Back"); context.clickScreenButton("Instant Build");
                awaitBlock(context, world, center.above(), Blocks.OAK_STAIRS);
                check(state(world, center.above()).getValue(StairBlock.FACING) == Direction.EAST, "preview rotation lost stair facing");
                context.waitFor(mc -> ClientState.string("selectedBuild", null) != null);
                String first = context.computeOnClient(mc -> ClientState.string("selectedBuild", null));
                command(context, "rename Workshop");

                // A real part-wand click pair selects one block with its exact height.
                world.getServer().runOnServer(server -> server.overworld().setBlock(center.west(), Blocks.DIAMOND_BLOCK.defaultBlockState(),
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
                openCreate(context); context.clickScreenButton("Editing tools"); context.clickScreenButton("Equip wand: Box");
                world.getServer().runCommand("gamemode creative " + player);
                world.getServer().runCommand("tp " + player + " " + (center.getX() - 0.5) + " " + (center.getY() + 2)
                        + " " + (center.getZ() + 0.5) + " 180 90");
                context.waitTicks(10);
                context.waitFor(mc -> mc.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(center.west()));
                context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT); context.waitTicks(5);
                context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT); context.waitTicks(5);
                context.waitFor(mc -> ClientState.part() != null);
                check(context.computeOnClient(mc -> ClientState.part().min().equals(center.west())
                        && ClientState.part().max().equals(center.west())), "part wand lost its height boundary");
                check(state(world, center.west()).is(Blocks.DIAMOND_BLOCK), "part wand changed a selected block");
                openCreate(context); context.clickScreenButton("Editing tools"); context.clickScreenButton("Locked areas");
                check(context.computeOnClient(mc -> mc.gui.screen().children().stream()
                        .filter(w -> w instanceof net.minecraft.client.gui.components.Button b1 && b1.getMessage().getString().equals("Lock area"))
                        .map(w -> !((net.minecraft.client.gui.components.Button) w).active).findFirst().orElse(false)),
                        "a blank name enabled the lock action");
                text(context, "Locked area name", "Desk"); context.clickScreenButton("Lock area");
                context.waitFor(mc -> ClientState.locks().size() == 1 && ClientState.part() == null);
                context.takeScreenshot("advanced-locked-area-controls");
                close(context); command(context, "reload");
                check(world.getServer().computeOnServer(server -> {
                    try { return new RegionStore(server.getWorldPath(LevelResource.ROOT).resolve("justbuild/locked-areas.json")).load().size(); }
                    catch (Exception e) { throw new AssertionError(e); }
                }) == 1, "the protected area was not saved");
                camera(context, world, player, center);
                screenshotWorld(context, "advanced-locked-area-outline");

                command(context, "box 1 " + pos(center.offset(-1, 0, -1)));
                command(context, "box 2 " + pos(center.offset(1, 0, 1)));
                replies.add(REVISION);
                openCreate(context); text(context, "Request", "replace the floor and staircase with stone");
                context.clickScreenButton("Apply"); awaitCalls(context, 2); awaitPreview(context);
                check(state(world, center).is(Blocks.OAK_PLANKS), "a focused edit placed before confirmation");
                command(context, "confirm");
                awaitBlock(context, world, center, Blocks.STONE);
                check(state(world, center.west()).is(Blocks.DIAMOND_BLOCK), "edit changed the locked manual detail");
                check(state(world, center.above()).is(Blocks.OAK_STAIRS), "edit changed blocks above the selected part");
                close(context); command(context, "box clear"); command(context, "undo"); command(context, "undo confirm");
                awaitBlock(context, world, center, Blocks.OAK_PLANKS);
                check(state(world, center.west()).is(Blocks.DIAMOND_BLOCK), "undo changed a locked block");

                openCreate(context); context.clickScreenButton("Manage build");
                text(context, "Design name", "Advanced Workshop"); context.clickScreenButton("Save design");
                context.waitFor(mc -> ClientState.designs().size() == 1);
                context.clickScreenButton("Back"); context.clickScreenButton("Designs");
                context.takeScreenshot("advanced-visual-design-library");
                context.clickScreenButton("Advanced Workshop");
                text(context, "Design folder", "Test houses"); context.clickScreenButton("Save folder");
                context.waitFor(mc -> ClientState.designs().getFirst().folder().equals("Test houses"));
                context.clickScreenButton("Export design");
                context.clickScreenButton("JustBuild · .justbuild.json");
                context.waitFor(mc -> ClientState.list("libraryFiles").stream().anyMatch(f -> f.startsWith("advanced-workshop-")));
                exported = context.computeOnClient(mc -> ClientState.list("libraryFiles").stream()
                        .filter(f -> f.startsWith("advanced-workshop-")).findFirst().orElseThrow());
                context.takeScreenshot("advanced-design-organize-export");
                context.clickScreenButton("Shared library"); context.takeScreenshot("advanced-portable-library");
                context.clickScreenButton("Import " + exported);
                context.waitFor(mc -> ClientState.designs().size() == 2);
                check(context.computeOnClient(mc -> ClientState.designs().stream().allMatch(design -> design.folder().equals("Test houses"))), "import lost the folder");
                close(context);

                command(context, "select clear"); zone(context, b, 5); camera(context, world, player, b);
                replies.add(plan(set("emerald_block", 0, 0, 0)));
                command(context, "build a second connection building"); awaitCalls(context, 3); awaitPreview(context); command(context, "confirm");
                awaitBlock(context, world, b, Blocks.EMERALD_BLOCK);
                command(context, "rename Garden house");
                String second = context.computeOnClient(mc -> ClientState.string("selectedBuild", null));
                openCreate(context); context.clickScreenButton("Editing tools"); context.clickScreenButton("Connections");
                command(context, "link a " + first); command(context, "link b " + second);
                text(context, "Connection request", "a stone brick path between these buildings");
                // Zone center is ten blocks east of A's original position. The AI deliberately overlaps both houses.
                replies.add(plan("{\"op\":\"fill\",\"block\":\"stone_bricks\",\"from\":[-9,0,0],\"to\":[10,0,0],\"hollow\":false}"));
                context.takeScreenshot("advanced-connection-controls"); context.clickScreenButton("Preview connection");
                awaitCalls(context, 4); awaitPreview(context); command(context, "confirm");
                awaitBlock(context, world, a.east(10), Blocks.STONE_BRICKS);
                check(state(world, center).is(Blocks.OAK_PLANKS), "the connector changed building A");
                check(state(world, b).is(Blocks.EMERALD_BLOCK), "the connector changed building B");
                check(prompts.stream().anyMatch(p -> p.contains("Connect building A")), "connection endpoints did not reach the AI");

                command(context, "select clear"); zone(context, d, 5); camera(context, world, player, d);
                replies.add(plan(set("lapis_block", 0, 0, 0))); replies.add(plan(set("gold_block", 0, 0, 0)));
                openCreate(context); context.clickScreenButton("Options: 1");
                text(context, "Request", "a small test marker"); context.clickScreenButton("Send ×2");
                awaitCalls(context, 6);
                context.waitFor(mc -> ClientState.preview() != null && ClientState.preview().options().size() == 2);
                check(state(world, d).isAir(), "an alternative was placed before choosing");
                command(context, "preview option 2"); command(context, "preview move 1 0 0");
                command(context, "preview option 1"); command(context, "preview option 2");
                check(context.computeOnClient(mc -> ClientState.preview().position().startsWith(d.east().toShortString())), "switching options lost the moved preview");
                context.takeScreenshot("advanced-alternative-controls"); context.clickScreenButton("Instant Build");
                awaitBlock(context, world, d.east(), Blocks.GOLD_BLOCK);
                check(state(world, d).isAir(), "an unchosen alternative changed the world");
                check(calls.get() == 6, "preview adjustments or library actions made extra AI calls");

                // An exhausted question budget on option 2 still makes the completed edit option available.
                replies.add(plan(set("emerald_block", 0, 0, 0)));
                replies.add("{\"kind\":\"question\",\"question\":\"Which material?\",\"options\":[\"Stone\",\"Gold\"]}");
                command(context, "alternatives edit 2 change the marker material"); awaitCalls(context, 8);
                context.waitFor(mc -> ClientState.preview() != null && ClientState.preview().options().size() == 1);
                check(state(world, d.east()).is(Blocks.GOLD_BLOCK), "an edit option was applied while collecting alternatives");
                command(context, "confirm"); awaitBlock(context, world, d.east(), Blocks.EMERALD_BLOCK);
                command(context, "undo"); command(context, "undo confirm"); awaitBlock(context, world, d.east(), Blocks.GOLD_BLOCK);

                // A damaged lock store must block an already prepared preview as well as new requests.
                command(context, "select clear"); zone(context, d.east(3), 1);
                replies.add(plan(set("lapis_block", 0, 0, 0)));
                command(context, "build a marker for the lock recovery test"); awaitCalls(context, 9); awaitPreview(context);
                var lockFile = world.getServer().computeOnServer(server -> server.getWorldPath(LevelResource.ROOT)
                        .resolve("justbuild/locked-areas.json"));
                try {
                    String savedLocks = Files.readString(lockFile);
                    try {
                        Files.writeString(lockFile, "{broken"); command(context, "reload");
                        command(context, "confirm");
                        check(context.computeOnClient(mc -> ClientState.preview() != null), "a failed confirmation lost the preview");
                        check(state(world, d.east(3)).isAir(), "a preview bypassed the damaged lock store");
                        String lockId = context.computeOnClient(mc -> ClientState.locks().getFirst().id());
                        command(context, "unlock " + lockId);
                        check(Files.readString(lockFile).equals("{broken"), "unlock overwrote a damaged lock store");
                        command(context, "build another marker");
                        check(calls.get() == 9, "a damaged lock store allowed a new AI request");
                    } finally { Files.writeString(lockFile, savedLocks); command(context, "reload"); }
                } catch (java.io.IOException e) { throw new AssertionError("Could not exercise lock recovery", e); }
                check(context.computeOnClient(mc -> ClientState.locks().size()) == 1, "reload lost a restored lock");
                command(context, "confirm"); awaitBlock(context, world, d.east(3), Blocks.LAPIS_BLOCK);
                check(calls.get() == 9, "confirming after repairing the lock store made another AI call");
            }

            // The same portable file is available in a new world, without the source record or its owner metadata.
            try (TestSingleplayerContext other = context.worldBuilder().create()) {
                other.getConnection().waitForChunksRender();
                context.waitFor(mc -> ClientState.designs().isEmpty());
                openCreate(context); context.clickScreenButton("Designs"); context.clickScreenButton("Shared library");
                context.clickScreenButton("Import " + exported);
                context.waitFor(mc -> ClientState.designs().size() == 1);
                check(context.computeOnClient(mc -> ClientState.designs().getFirst().name().equals("Advanced Workshop")), "cross-world import lost the design name");
                check(calls.get() == 9, "cross-world import used the AI");
                context.takeScreenshot("advanced-cross-world-import"); close(context);
            }
        } finally {
            stub.stop(0);
            try { config.save(JustBuildConfig.defaults()); }
            catch (Exception e) { throw new AssertionError("Could not reset advanced test config", e); }
        }
    }

    private static void platform(TestSingleplayerContext world, BlockPos at, int width) {
        world.getServer().runOnServer(server -> {
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-10, -1, -10), at.offset(width, 5, 10))) {
                server.overworld().setBlock(p, p.getY() == at.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        });
    }
    private static void camera(ClientGameTestContext context, TestSingleplayerContext world, String name, BlockPos at) {
        world.getServer().runCommand("gamemode spectator " + name);
        world.getServer().runCommand("tp " + name + " " + (at.getX() + 0.5) + " " + (at.getY() + 5)
                + " " + (at.getZ() + 9.5) + " 180 28");
        context.waitTicks(10);
    }
    private static String pos(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
    private static void zone(ClientGameTestContext context, BlockPos at, int radius) {
        command(context, "zone 1 " + pos(at.offset(-radius, -1, -radius)));
        command(context, "zone 2 " + pos(at.offset(radius, -1, radius)));
    }
    private static void command(ClientGameTestContext context, String text) {
        context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + text)); context.waitTicks(8);
    }
    private static void openCreate(ClientGameTestContext context) {
        context.runOnClient(mc -> mc.gui.setScreen(new JustBuildScreen())); context.tryClickScreenButton("Create"); context.waitTicks(5);
    }
    private static void close(ClientGameTestContext context) { context.runOnClient(mc -> mc.gui.setScreen(null)); }
    private static void text(ClientGameTestContext context, String label, String value) {
        context.runOnClient(mc -> ((EditBox) mc.gui.screen().children().stream().filter(w -> w instanceof EditBox e
                && e.getMessage().getString().equals(label)).findFirst().orElseThrow()).setValue(value));
    }
    private static void screenshotWorld(ClientGameTestContext context, String name) {
        context.runOnClient(mc -> dev.justbuild.client.TestHud.hidden(mc, true)); context.waitTicks(3); context.takeScreenshot(name);
        context.runOnClient(mc -> dev.justbuild.client.TestHud.hidden(mc, false));
    }
    private void awaitCalls(ClientGameTestContext context, int count) {
        for (int i = 0; i < 150 && calls.get() < count; i++) context.waitTicks(5);
        check(calls.get() == count, "expected " + count + " AI calls, got " + calls.get()); context.waitTicks(12);
    }
    private static void awaitPreview(ClientGameTestContext context) { context.waitFor(mc -> ClientState.preview() != null); }
    private static BlockState state(TestSingleplayerContext world, BlockPos p) {
        return world.getServer().computeOnServer(server -> server.overworld().getBlockState(p));
    }
    private static void awaitBlock(ClientGameTestContext context, TestSingleplayerContext world, BlockPos p, Block block) {
        for (int i = 0; i < 100 && !state(world, p).is(block); i++) context.waitTicks(5);
        check(state(world, p).is(block), "expected " + block + " at " + p + ", got " + state(world, p)); context.waitTicks(10);
    }
    private static String fill(String block) { return "{\"op\":\"fill\",\"block\":\"" + block + "\",\"from\":[-1,0,-1],\"to\":[1,0,1],\"hollow\":false}"; }
    private static String set(String block, int x, int y, int z) { return "{\"op\":\"set\",\"block\":\"" + block + "\",\"from\":[" + x + "," + y + "," + z + "]}"; }
    private static String plan(String... operations) { return "{\"kind\":\"build\",\"title\":\"Advanced test\",\"summary\":\"\",\"operations\":[" + String.join(",", operations) + "]}"; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private HttpServer stub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), review = StubReview.draft(body);
                if (review == null) { prompts.add(body); calls.incrementAndGet(); }
                String response = review != null ? review : replies.poll();
                JsonObject message = new JsonObject(); message.addProperty("content", response == null
                        ? "{\"kind\":\"decline\",\"summary\":\"Unexpected call\"}" : response);
                JsonObject choice = new JsonObject(); choice.add("message", message);
                JsonArray choices = new JsonArray(); choices.add(choice); JsonObject reply = new JsonObject(); reply.add("choices", choices);
                byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start(); return server;
        } catch (Exception e) { throw new AssertionError("Could not start advanced test AI", e); }
    }
    private static void configure(ConfigStore store, int port) {
        try {
            JustBuildConfig config = JustBuildConfig.defaults();
            var stub = new JustBuildConfig.ModelProfile("Stub", "openai", "stub"); stub.baseUrl = "http://127.0.0.1:" + port + "/v1";
            config.replaceProfiles(List.of(stub), "Stub"); config.previewAbove = 0; config.detail = "quick"; config.creativeDirection = false;
            config.maxQuestions = 0;
            store.save(config);
            var library = FabricLoader.getInstance().getGameDir().resolve("justbuild/library");
            if (Files.isDirectory(library)) try (var files = Files.newDirectoryStream(library, "advanced-workshop-*.justbuild.json")) {
                for (var file : files) Files.delete(file);
            }
        } catch (Exception e) { throw new AssertionError("Could not prepare advanced tests", e); }
    }
}
