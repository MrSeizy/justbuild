package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import dev.justbuild.world.BuildRecord;
import dev.justbuild.world.BuildStore;
import dev.justbuild.world.DesignStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
import org.lwjgl.glfw.GLFW;

/** Switch between houses, preserve a rejected edit, restore checkpoints, and copy a rotated design through the UI. */
public final class BuildWorkspaceGameTest implements FabricClientGameTest {
    private static final String FLOOR = "{\"op\":\"fill\",\"block\":\"oak_planks\",\"from\":[-1,0,-1],\"to\":[1,0,1],\"hollow\":false}";
    private static final String STAIRS = "{\"op\":\"set\",\"block\":\"oak_stairs[facing=north]\",\"from\":[0,1,0]}";
    private static final String CHIMNEY = "{\"op\":\"set\",\"block\":\"cobblestone\",\"from\":[-1,1,-1]}";
    private static final String ORIGINAL = plan(FLOOR, STAIRS, CHIMNEY);
    private static final String EXTENDED = plan(FLOOR,
            "{\"op\":\"set\",\"block\":\"stone_brick_stairs[facing=north]\",\"from\":[0,1,0]}",
            "{\"op\":\"fill\",\"block\":\"oak_planks\",\"from\":[2,0,-1],\"to\":[4,0,1],\"hollow\":false}");
    private final Queue<String> replies = new ConcurrentLinkedQueue<>();
    private final AtomicInteger calls = new AtomicInteger(), reviews = new AtomicInteger();

    @Override public void runTest(ClientGameTestContext context) {
        HttpServer stub = stub();
        ConfigStore config = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("justbuild.json"));
        configure(config, stub.getAddress().getPort());
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            BlockPos a = context.computeOnClient(mc -> mc.player.blockPosition().offset(0, 18, 10));
            BlockPos b = a.east(20), c = a.east(40);
            String player = context.computeOnClient(mc -> mc.player.getName().getString());
            world.getServer().runOnServer(server -> {
                for (BlockPos center : List.of(a, b, c)) for (BlockPos pos : BlockPos.betweenClosed(center.offset(-7, -1, -7), center.offset(7, 6, 7))) {
                    server.overworld().setBlock(pos, pos.getY() == center.getY() - 1 ? Blocks.STONE.defaultBlockState()
                            : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            });
            camera(context, world, player, a);
            zone(context, a);
            replies.add(ORIGINAL);
            openCreate(context);
            text(context, "Request", "design a small test cottage");
            context.clickScreenButton("Build");
            awaitCalls(context, 1);
            context.runOnClient(mc -> mc.screen.onClose());
            command(context, "another request while the preview is pending");
            check(calls.get() == 1, "a waiting preview allowed a competing AI request");
            check(state(world, a).isAir(), "new-build preview changed the server world");
            screenshotWorld(context, "workspace-native-block-ghosts");
            command(context, "confirm");
            awaitBlock(context, world, a.above(), Blocks.OAK_STAIRS);
            // AI review "When needed": these stairs have no landing, so the draft got one review.
            check(reviews.get() == 1, "the draft with a stair warning got " + reviews.get() + " reviews, not 1");
            context.waitFor(mc -> ClientState.string("selectedBuild", null) != null);
            String first = context.computeOnClient(mc -> ClientState.string("selectedBuild", null));

            openCreate(context);
            context.clickScreenButton("Manage build");
            context.waitForScreen(BuildDetailsScreen.class);
            text(context, "Build name", "Lakeside House");
            context.clickScreenButton("Rename");
            context.waitFor(mc -> "Lakeside House".equals(ClientState.string("selectedTitle", "")));
            text(context, "Checkpoint name", "Original house");
            context.clickScreenButton("Save checkpoint");
            context.waitFor(mc -> ClientState.list("checkpoints").contains("Original house"));
            // Save designs from actual placed states, including a hand-edited block at a known position.
            world.getServer().runOnServer(server -> server.overworld().setBlock(a.west(), Blocks.DIAMOND_BLOCK.defaultBlockState(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
            text(context, "Design name", "Reusable cottage");
            context.clickScreenButton("Save design");
            context.waitFor(mc -> ClientState.designs().size() == 1);
            world.getServer().runOnServer(server -> server.overworld().setBlock(a.west(), Blocks.OAK_PLANKS.defaultBlockState(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
            context.takeScreenshot("workspace-build-details");
            context.clickScreenButton("Back");
            context.clickScreenButton("New build");
            context.waitFor(mc -> ClientState.string("selectedBuild", null) == null);
            context.runOnClient(mc -> mc.screen.onClose());

            // A second build gets its own area; naming it must leave the first build's area untouched.
            zone(context, b);
            camera(context, world, player, b);
            replies.add(plan("{\"op\":\"set\",\"block\":\"emerald_block\",\"from\":[0,0,0]}"));
            command(context, "a second test house");
            awaitCalls(context, 2);
            command(context, "confirm");
            awaitBlock(context, world, b, Blocks.EMERALD_BLOCK);
            check(reviews.get() == 1, "a draft with no warnings was sent for review");
            command(context, "rename Hilltop House");
            String second = context.computeOnClient(mc -> ClientState.string("selectedBuild", null));
            command(context, "select #" + second);
            check(!first.equals(second), "the second build reused the first build's id");
            check(saved(world, first).zone().minX() == a.getX() - 2, "drawing the second area changed the first saved area");

            // A real sneak-right-click picks the first build and restores its saved area.
            command(context, "zone wand");
            world.getServer().runCommand("gamemode creative " + player);
            world.getServer().runCommand("tp " + player + " " + (a.getX() + 0.5) + " " + (a.getY() + 2)
                    + " " + (a.getZ() + 0.5) + " 180 90");
            context.waitTicks(10);
            context.waitFor(mc -> mc.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(a.above()));
            context.getInput().holdShift();
            context.waitTicks(5);
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            context.waitTicks(10);
            context.getInput().releaseShift();
            context.waitFor(mc -> first.equals(ClientState.string("selectedBuild", null)));
            check(context.computeOnClient(mc -> ClientState.string("zoneLabel", "").contains("x=" + (a.getX() - 2))),
                    "wand selection did not restore the first build's area");

            // A build remains selectable from its excluded blocks, even with no visible zone at all.
            command(context, "zone 1 " + (a.getX() - 2) + " " + (a.getY() - 1) + " " + (a.getZ() - 2));
            command(context, "zone 2 " + (a.getX() - 1) + " " + (a.getY() - 1) + " " + (a.getZ() + 2));
            var smallerZone = saved(world, first).zone();
            check(!smallerZone.contains(a.above()), "the resized zone still includes the clicked build block");
            command(context, "new");
            check(context.computeOnClient(mc -> smallerZone.equals(ClientState.zone())),
                    "new discarded a manually drawn zone");
            command(context, "zone clear");
            check(context.computeOnClient(mc -> ClientState.zone() == null
                    && ClientState.string("selectedBuild", null) == null), "selection was not cleared before the wand test");
            long beforeSelection = context.computeOnClient(mc -> ClientState.lineCount());
            sneakSelect(context);
            check(context.computeOnClient(mc -> {
                var message = ClientState.lastLineSince(beforeSelection);
                return message != null && message.getString().contains("Editing “Lakeside House”.")
                        && message.getString().contains("Loaded its saved zone.")
                        && !message.getString().contains("/justbuild select");
            }), "wand selection did not select the whole build directly from a block outside its zone");
            check(context.computeOnClient(mc -> first.equals(ClientState.string("selectedBuild", null))
                    && smallerZone.equals(ClientState.zone())), "wand selection did not reveal the saved zone");
            check(state(world, a.above()).is(Blocks.OAK_STAIRS), "wand selection changed the clicked block");
            context.runOnClient(mc -> mc.gui.getChat().clearMessages(false));
            context.takeScreenshot("workspace-wand-restores-excluded-zone");

            // Switching buildings must not carry the previous building's zone along with it.
            command(context, "select #" + second);
            var secondZone = saved(world, second).zone();
            sneakSelect(context);
            check(context.computeOnClient(mc -> first.equals(ClientState.string("selectedBuild", null))
                    && smallerZone.equals(ClientState.zone())), "wand selection carried another building's zone");

            // An unassigned zone drawn around the target is kept and saved, not replaced by the old small zone.
            command(context, "new");
            check(context.computeOnClient(mc -> ClientState.zone() == null), "new kept a zone loaded from a saved build");
            zone(context, a);
            var drawnZone = context.computeOnClient(mc -> ClientState.zone());
            command(context, "new");
            check(context.computeOnClient(mc -> drawnZone.equals(ClientState.zone())
                    && ClientState.string("selectedBuild", null) == null), "new did not keep an unassigned drawn zone");
            check(smallerZone.equals(saved(world, first).zone()), "drawing an unassigned zone changed the saved build");
            sneakSelect(context);
            check(context.computeOnClient(mc -> first.equals(ClientState.string("selectedBuild", null))
                    && drawnZone.equals(ClientState.zone())), "wand selection discarded the newly drawn zone");
            check(drawnZone.equals(saved(world, first).zone()), "wand selection did not save the newly drawn zone");
            check(secondZone.equals(saved(world, second).zone()), "wand selection changed another building's saved zone");
            check(state(world, a.above()).is(Blocks.OAK_STAIRS) && calls.get() == 2,
                    "selecting a build changed blocks or called the AI");

            camera(context, world, player, a);
            openCreate(context);
            context.takeScreenshot("workspace-selected-house");

            // Plain panel text now edits the selected house. A crossing plan is kept and retried without another call.
            replies.add(EXTENDED);
            text(context, "Request", "style the stairs and add a room");
            context.clickScreenButton("Apply");
            awaitCalls(context, 3);
            context.waitFor(mc -> ClientState.string("retainedPlan", null) != null);
            context.takeScreenshot("workspace-retained-plan");
            check(state(world, a.east(4)).isAir(), "a rejected edit placed outside its area");
            command(context, "zone expand 4");
            command(context, "select clear");
            context.clickScreenButton("Retry saved plan");
            context.waitTicks(20);
            check(context.computeOnClient(mc -> first.equals(ClientState.string("selectedBuild", null))),
                    "retry did not make its original edit target visible again");
            context.runOnClient(mc -> mc.screen.onClose());
            screenshotWorld(context, "workspace-change-preview");
            check(calls.get() == 3, "retry generated another AI plan");
            command(context, "edit #" + second + " change the other house while this preview is pending");
            check(calls.get() == 3, "a waiting edit preview allowed an AI request for another build");
            check(state(world, a.above()).is(Blocks.OAK_STAIRS), "retry placed changes before confirmation");
            command(context, "confirm");
            awaitBlock(context, world, a.east(4), Blocks.OAK_PLANKS);
            awaitBlock(context, world, a.above(), Blocks.STONE_BRICK_STAIRS);
            check(state(world, a.offset(-1, 1, -1)).isAir(), "the planned removal was not applied");
            check(state(world, b).is(Blocks.EMERALD_BLOCK), "editing the first house changed the second");
            check("Lakeside House".equals(saved(world, first).title()), "an AI edit replaced the custom build name");

            openCreate(context);
            context.clickScreenButton("Manage build");
            context.clickScreenButton("Restore: Original house");
            context.waitTicks(20);
            check(state(world, a.east(4)).is(Blocks.OAK_PLANKS), "checkpoint restored without a preview");
            screenshotWorld(context, "workspace-checkpoint-preview");
            command(context, "confirm");
            awaitBlock(context, world, a.above(), Blocks.OAK_STAIRS);
            awaitBlock(context, world, a.east(4), Blocks.AIR);
            check(saved(world, first).versions().size() == 3, "checkpoint restore did not create an undoable version");
            command(context, "undo");
            command(context, "undo confirm");
            awaitBlock(context, world, a.east(4), Blocks.OAK_PLANKS);
            check(saved(world, first).checkpoints().size() == 1, "undo deleted the named checkpoint");

            // A saved design survives source edits; copying it uses its own origin, orientation and palette.
            command(context, "select clear");
            zone(context, c);
            camera(context, world, player, c);
            openCreate(context);
            context.clickScreenButton("Designs");
            context.clickScreenButton("Reusable cottage");
            context.waitForScreen(DesignPlacementScreen.class);
            context.clickScreenButton("Rotation: 0°");
            swap(context, "oak_planks", "Oak Planks", "spruce_planks", "Spruce Planks");
            swap(context, "oak_stairs", "Oak Stairs", "spruce_stairs", "Spruce Stairs");
            context.takeScreenshot("workspace-design-copy-controls");
            context.clickScreenButton("Preview copy");
            context.waitTicks(20);
            check(state(world, c).isAir(), "copy placed before confirmation");
            screenshotWorld(context, "workspace-design-copy-preview");
            command(context, "confirm");
            awaitBlock(context, world, c, Blocks.SPRUCE_PLANKS);
            awaitBlock(context, world, c.above(), Blocks.SPRUCE_STAIRS);
            check(state(world, c.above()).getValue(StairBlock.FACING) == Direction.EAST, "copy did not rotate block state facing");
            check(state(world, c.north()).is(Blocks.DIAMOND_BLOCK), "saved design lost a manual change at a known build position");
            check(state(world, c.offset(1, 1, -1)).is(Blocks.COBBLESTONE), "copy did not rotate the saved design's geometry");
            check(state(world, a.above()).is(Blocks.STONE_BRICK_STAIRS), "copy changed the source build");
            check(state(world, b).is(Blocks.EMERALD_BLOCK), "copy changed the other house");
            check(calls.get() == 3, "selection, saving, restoring, or copying made an AI call");
            check(world.getServer().computeOnServer(server -> new DesignStore(server.getWorldPath(LevelResource.ROOT)
                    .resolve("justbuild/designs")).loadAll().size()) == 1, "design was not saved to the world");
            command(context, "select clear");
            command(context, "edit this should require a selection");
            check(calls.get() == 3, "edit silently fell back to the latest build");

            // A retry rejected by changed limits must clear the obsolete retry action.
            zone(context, c.east(20));
            replies.add(EXTENDED);
            command(context, "build a final test cottage");
            awaitCalls(context, 4);
            context.waitFor(mc -> ClientState.string("retainedPlan", null) != null);
            command(context, "retry");
            check(context.computeOnClient(mc -> ClientState.string("retainedPlan", null) != null),
                    "retrying before enlarging the area lost the saved plan");
            try {
                JustBuildConfig reduced = config.load();
                reduced.maxBlocks = 1;
                config.save(reduced);
            } catch (Exception e) { throw new AssertionError("Could not lower the retry limit", e); }
            command(context, "reload");
            command(context, "retry");
            context.waitFor(mc -> ClientState.string("retainedPlan", null) == null);
            check(calls.get() == 4, "retrying a rejected plan made another AI call");
        } finally {
            stub.stop(0);
            try { config.save(JustBuildConfig.defaults()); }
            catch (Exception e) { throw new AssertionError("Could not reset test config", e); }
        }
    }

    private static void openCreate(ClientGameTestContext context) {
        context.runOnClient(mc -> mc.setScreen(new JustBuildScreen()));
        context.tryClickScreenButton("Create");
        context.waitTicks(5);
    }

    private static void screenshotWorld(ClientGameTestContext context, String name) {
        context.runOnClient(mc -> mc.options.hideGui = true);
        context.waitTicks(3);
        context.takeScreenshot(name);
        context.runOnClient(mc -> mc.options.hideGui = false);
    }

    /** Adds one material swap to the design copy with the block picker. */
    private static void swap(ClientGameTestContext context, String fromSearch, String from, String toSearch, String to) {
        context.clickScreenButton("Swap materials…");
        context.waitForScreen(MaterialPickerScreen.class);
        text(context, "Search blocks", fromSearch);
        context.clickScreenButton("   " + from);
        text(context, "Search blocks", toSearch);
        context.clickScreenButton("   " + to);
        context.waitForScreen(DesignPlacementScreen.class);
    }

    private static void text(ClientGameTestContext context, String label, String value) {
        context.runOnClient(mc -> {
            EditBox box = (EditBox) mc.screen.children().stream().filter(c -> c instanceof EditBox e
                    && e.getMessage().getString().equals(label)).findFirst().orElseThrow();
            box.setValue(value);
        });
    }

    private static void camera(ClientGameTestContext context, TestSingleplayerContext world, String name, BlockPos at) {
        world.getServer().runCommand("gamemode spectator " + name);
        world.getServer().runCommand("tp " + name + " " + (at.getX() + 6.5) + " " + (at.getY() + 5)
                + " " + (at.getZ() + 10.5) + " 150 24");
        // Keep the source's facing north while allowing an angled screenshot.
        context.waitTicks(10);
    }

    private static void zone(ClientGameTestContext context, BlockPos at) {
        command(context, "zone 1 " + (at.getX() - 2) + " " + (at.getY() - 1) + " " + (at.getZ() - 2));
        command(context, "zone 2 " + (at.getX() + 2) + " " + (at.getY() - 1) + " " + (at.getZ() + 2));
    }

    private static void command(ClientGameTestContext context, String text) {
        context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + text));
        context.waitTicks(10);
    }

    private static void sneakSelect(ClientGameTestContext context) {
        context.getInput().holdShift();
        context.waitTicks(5);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        context.waitTicks(10);
        context.getInput().releaseShift();
        context.waitTicks(2);
    }

    private void awaitCalls(ClientGameTestContext context, int count) {
        for (int i = 0; i < 100 && calls.get() < count; i++) context.waitTicks(5);
        check(calls.get() == count, "expected " + count + " AI calls, got " + calls.get());
        context.waitTicks(20);
    }

    private static BlockState state(TestSingleplayerContext world, BlockPos pos) {
        return world.getServer().computeOnServer(server -> server.overworld().getBlockState(pos));
    }

    private static void awaitBlock(ClientGameTestContext context, TestSingleplayerContext world, BlockPos pos, Block block) {
        for (int i = 0; i < 100 && !state(world, pos).is(block); i++) context.waitTicks(5);
        check(state(world, pos).is(block), "expected " + block + " at " + pos + ", got " + state(world, pos));
        context.waitTicks(10);
    }

    private static BuildRecord saved(TestSingleplayerContext world, String id) {
        return world.getServer().computeOnServer(server -> new BuildStore(server.getWorldPath(LevelResource.ROOT)
                .resolve("justbuild/builds")).loadAll().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow());
    }

    private static String plan(String... operations) {
        return "{\"kind\":\"build\",\"title\":\"Test cottage\",\"summary\":\"\",\"operations\":[" + String.join(",", operations) + "]}";
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private HttpServer stub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                String review = StubReview.draft(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                (review == null ? calls : reviews).incrementAndGet();
                String response = review != null ? review : replies.poll();
                JsonObject message = new JsonObject();
                message.addProperty("content", response == null ? "{\"kind\":\"decline\",\"summary\":\"Unexpected call\"}" : response);
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
            server.start();
            return server;
        } catch (Exception e) { throw new AssertionError("Could not start test AI", e); }
    }

    private static void configure(ConfigStore store, int port) {
        try {
            JustBuildConfig config = JustBuildConfig.defaults();
            JustBuildConfig.ModelProfile stub = new JustBuildConfig.ModelProfile("Stub", "openai", "stub");
            stub.baseUrl = "http://127.0.0.1:" + port + "/v1";
            config.replaceProfiles(List.of(stub), "Stub");
            config.previewAbove = 0;
            config.detail = "quick";
            config.creativeDirection = false;
            store.save(config);
        } catch (Exception e) { throw new AssertionError("Could not prepare test config", e); }
    }
}
