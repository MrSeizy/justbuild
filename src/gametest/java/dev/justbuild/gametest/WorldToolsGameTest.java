package dev.justbuild.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.JustBuildMod;
import dev.justbuild.client.JustBuildScreen;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.InspectionRequest;
import dev.justbuild.world.WorldInspector;
import dev.justbuild.world.ZoneWand;
import dev.justbuild.world.BuildStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;

/** Real server reads, rotated plans, previews, restricted edit diffs, and undo. All model calls use a local stub. */
public final class WorldToolsGameTest implements FabricClientGameTest {
    private static final String INSPECT = """
            {"kind":"inspect","inspection":{"from":[-2,0,-2],"to":[2,2,2]}}
            """;
    private static final String CLOSE = """
            {"kind":"inspect","inspection":{"from":[0,1,0],"to":[0,1,0]}}
            """;
    private static final String FLOOR = """
            {"op":"fill","block":"stone_bricks","from":[-1,0,-1],"to":[1,0,1],"hollow":false}
            """;
    private static final String GOLD = """
            {"op":"set","block":"gold_block","from":[0,1,0]}
            """;
    private static final String OUTSIDE = """
            {"op":"set","block":"emerald_block","from":[2,1,0]}
            """;

    private final Queue<String> replies = new ConcurrentLinkedQueue<>();
    private final List<String[]> calls = new CopyOnWriteArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        HttpServer stub = startStub();
        ConfigStore store = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("justbuild.json"));
        configure(store, stub.getAddress().getPort());
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            BlockPos feet = context.computeOnClient(mc -> mc.player.blockPosition());
            BlockPos origin = feet.offset(10, 14, 10);
            BlockState stair = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
            world.getServer().computeOnServer(server -> {
                var level = server.overworld();
                for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-3, -1, -3), origin.offset(3, 4, 3))) {
                    level.setBlock(pos, pos.getY() == origin.getY() - 1 ? Blocks.STONE.defaultBlockState()
                            : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
                level.setBlock(origin.south(), stair, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                var view = WorldInspector.inspect(level, origin, Direction.EAST,
                        new InspectionRequest(new Cell(1, 0, 0), new Cell(1, 0, 0)));
                check(view.palette().size() == 1 && view.palette().getFirst().contains("facing=north"),
                        "inspection block positions and states must rotate back into the plan frame");
                BlockPos remote = new BlockPos(20_000_008, 64, 20_000_008);
                check(!level.hasChunk(remote.getX() >> 4, remote.getZ() >> 4), "remote test chunk was already loaded");
                var unknown = WorldInspector.inspect(level, remote, Direction.NORTH,
                        new InspectionRequest(new Cell(-1, -1, -1), new Cell(1, 1, 1)));
                check(unknown.palette().isEmpty(), "unloaded chunks must produce unknown samples");
                check(!level.hasChunk(remote.getX() >> 4, remote.getZ() >> 4), "inspection loaded a chunk");
                return true;
            });
            String name = context.computeOnClient(mc -> mc.player.getName().getString());
            selectWithWand(context, world, origin, name);
            world.getServer().runCommand("gamemode spectator " + name);
            world.getServer().runCommand("tp " + name + " " + (origin.getX() - 8.5) + " " + (origin.getY() + 4)
                    + " " + (origin.getZ() + 0.5) + " -90 20");
            context.waitTicks(10);
            context.runOnClient(mc -> mc.options.hideGui = true);
            context.waitTicks(5);
            context.takeScreenshot("world-zone-footprint");
            context.runOnClient(mc -> mc.options.hideGui = false);
            command(context, "inspect");
            check(calls.isEmpty(), "manual inspection called the AI");
            check(state(world, origin.south()).is(Blocks.OAK_STAIRS), "manual inspection changed blocks");

            replies.add(INSPECT);
            replies.add(plan(FLOOR));
            int phase = calls.size();
            command(context, "a small stone platform");
            awaitCalls(context, phase + 2);
            check(calls.get(0)[1].contains("Selected build zone") && calls.get(0)[1].contains("Live world inspection"),
                    "initial request omitted the zone or live view");
            check(calls.get(0)[1].contains("x=-2..2, z=-2..2"), "wand clicks did not select the expected footprint");
            check(calls.get(1)[1].contains("facing=north"), "closer inspection lost the stairs' plan-facing state");
            check(saved(world) == 0, "preview placed blocks before confirmation");
            // Changing the boundary while the preview is pending must be refused.
            command(context, "zone clear");
            command(context, "zone expand 8");
            world.getServer().runCommand("gamemode creative " + name);
            lookDownAt(context, world, name, origin.offset(3, -1, 3));
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            context.waitTicks(10);
            world.getServer().runCommand("gamemode spectator " + name);
            world.getServer().runCommand("tp " + name + " " + (origin.getX() - 8.5) + " " + (origin.getY() + 4)
                    + " " + (origin.getZ() + 0.5) + " -90 20");
            context.waitTicks(10);
            command(context, "confirm");
            awaitBlock(context, world, origin, Blocks.STONE_BRICKS);
            check(saved(world) == 1, "confirmed build was not saved");
            String firstId = world.getServer().computeOnServer(server -> new BuildStore(server.getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT).resolve("justbuild/builds")).loadAll().getFirst().id());

            // A plan that crosses the still-active footprint is rejected in full.
            replies.add(plan(FLOOR, "{\"op\":\"set\",\"block\":\"emerald_block\",\"from\":[4,1,0]}"));
            phase = calls.size();
            command(context, "another platform reaching outside the plot");
            awaitCalls(context, phase + 2);
            command(context, "confirm");
            check(saved(world) == 1, "the zone was cleared while a preview was pending, or an outside plan was accepted");
            check(!state(world, origin.south(4).above()).is(Blocks.EMERALD_BLOCK), "out-of-zone block was placed");

            // Limit edits to one column; unchanged parts of the complete plan may remain outside it.
            command(context, "select " + firstId);
            zone(context, origin, origin);
            world.getServer().computeOnServer(server -> server.overworld().setBlock(origin.above(),
                    Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS));
            replies.add(CLOSE);
            replies.add(plan(FLOOR, GOLD));
            phase = calls.size();
            command(context, "edit replace the diamond above the center with gold");
            awaitCalls(context, phase + 2);
            check(calls.get(phase)[1].contains("minecraft:diamond_block"), "edit did not read fresh world changes");
            check(calls.get(phase + 1)[1].contains("minecraft:diamond_block"), "edit's closer inspection was not returned");
            command(context, "confirm");
            awaitBlock(context, world, origin.above(), Blocks.GOLD_BLOCK);
            check(state(world, origin.south()).is(Blocks.STONE_BRICKS), "unchanged outside portion of edit was altered");

            replies.add(plan(FLOOR, GOLD, OUTSIDE));
            phase = calls.size();
            command(context, "edit add emerald outside the selected column");
            awaitCalls(context, phase + 2);
            command(context, "confirm");
            check(!state(world, origin.south(2).above()).is(Blocks.EMERALD_BLOCK), "out-of-zone edit was placed");

            int afterBoundaryReview = calls.size();
            // Manual expansion opens room for the same edit while retaining the saved build's origin.
            command(context, "zone expand 2");
            command(context, "zone expand 127"); // Too large: leave the previous footprint intact.
            context.runOnClient(mc -> mc.setScreen(new JustBuildScreen()));
            context.tryClickScreenButton("World");
            command(context, "zone expand 4");
            context.waitTicks(10);
            context.takeScreenshot("world-zone-controls");
            context.runOnClient(mc -> mc.screen.onClose());
            // Looking upward gives no block hit, so expansion includes the position underfoot.
            world.getServer().runCommand("tp " + name + " " + (origin.getX() + 0.5) + " " + (origin.getY() + 4)
                    + " " + (origin.getZ() + 8.5) + " -90 -90");
            context.waitTicks(10);
            command(context, "zone include");
            world.getServer().runCommand("tp " + name + " " + (origin.getX() - 8.5) + " " + (origin.getY() + 4)
                    + " " + (origin.getZ() + 0.5) + " -90 20");
            context.waitTicks(10);
            check(calls.size() == afterBoundaryReview, "zone expansion called the AI");
            replies.add(plan(FLOOR, GOLD, OUTSIDE));
            phase = calls.size();
            command(context, "edit add emerald in the expanded area");
            awaitCalls(context, phase + 1);
            check(calls.get(phase)[1].contains("x=-6..8, z=-6..6"),
                    "expansion did not preserve the old area, include the target, or keep the edit origin");
            command(context, "confirm");
            awaitBlock(context, world, origin.south(2).above(), Blocks.EMERALD_BLOCK);
            command(context, "undo");
            command(context, "undo confirm");
            awaitBlock(context, world, origin.south(2).above(), Blocks.AIR);
            zone(context, origin, origin);

            command(context, "undo");
            command(context, "undo confirm");
            awaitBlock(context, world, origin.above(), Blocks.DIAMOND_BLOCK);
            command(context, "undo");
            command(context, "undo confirm");
            check(state(world, origin).is(Blocks.STONE_BRICKS), "undo crossed the selected footprint");
            command(context, "zone clear");
            command(context, "undo");
            command(context, "undo confirm");
            awaitBlock(context, world, origin.south(), Blocks.OAK_STAIRS);
            check(saved(world) == 0, "undo did not remove the build after the zone was cleared");

            // Clarifying answers retain the close view, refreshed without spending another inspection round.
            replies.add(CLOSE);
            replies.add("{\"kind\":\"question\",\"question\":\"Which material?\",\"options\":[\"stone\"]}");
            phase = calls.size();
            command(context, "a platform after checking the center");
            awaitCalls(context, phase + 2);
            replies.add(plan(FLOOR));
            phase = calls.size();
            command(context, "answer stone");
            awaitCalls(context, phase + 1);
            check(calls.get(phase)[1].contains("x=0..0, y=1..1, z=0..0"),
                    "clarifying answer lost the earlier close inspection");
            check(calls.get(phase)[0].contains("3 requests left"), "clarifying answer reset the inspection budget");
            command(context, "cancel");

            for (int i = 0; i < InspectionRequest.MAX_ROUNDS + 1; i++) replies.add(INSPECT);
            phase = calls.size();
            command(context, "a platform after inspecting forever");
            awaitCalls(context, phase + InspectionRequest.MAX_ROUNDS + 1);
            context.waitTicks(20);
            check(calls.size() == phase + InspectionRequest.MAX_ROUNDS + 1 && saved(world) == 0, "inspection loop exceeded its budget or placed a build");
            check(calls.getLast()[0].contains("World inspection is not available"), "last call still offered more inspections");
        } finally {
            stub.stop(0);
            try {
                store.save(JustBuildConfig.defaults());
            } catch (Exception e) {
                throw new AssertionError("Could not reset test config", e);
            }
        }
    }

    private void selectWithWand(ClientGameTestContext context, TestSingleplayerContext world, BlockPos origin, String name) {
        world.getServer().runCommand("gamemode creative " + name);
        // A full inventory must never lose a stack when requesting a new wand.
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            var inventory = player.getInventory();
            inventory.clearContent();
            for (int i = 0; i < inventory.getNonEquipmentItems().size(); i++) {
                inventory.setItem(i, new ItemStack(Items.DIAMOND, 64));
            }
            player.inventoryMenu.broadcastChanges();
        });
        command(context, "zone wand");
        check(itemCount(world, Items.DIAMOND) == 36 * 64 && itemCount(world, ZoneWand.ITEM) == 0,
                "requesting a wand overwrote a full inventory");
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            var inventory = player.getInventory();
            inventory.clearContent();
            inventory.setSelectedSlot(0);
            for (int i = 0; i < 9; i++) {
                inventory.setItem(i, new ItemStack(Items.GOLD_INGOT, 7));
            }
            player.inventoryMenu.broadcastChanges();
        });
        context.runOnClient(mc -> mc.setScreen(new JustBuildScreen()));
        context.tryClickScreenButton("World");
        context.clickScreenButton("Wand: Zone");
        context.waitFor(mc -> mc.screen == null && mc.player.getMainHandItem().is(ZoneWand.ITEM));
        command(context, "zone wand");
        check(itemCount(world, ZoneWand.ITEM) == 1 && itemCount(world, Items.GOLD_INGOT) == 63,
                "equipping the wand duplicated it or lost a hotbar stack");
        // Re-equipping also preserves items when the wand is stored outside the hotbar or in the offhand.
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            var inventory = player.getInventory();
            inventory.setItem(12, inventory.setSelectedItem(ItemStack.EMPTY));
            player.inventoryMenu.broadcastChanges();
        });
        command(context, "zone wand");
        check(context.computeOnClient(mc -> mc.player.getMainHandItem().is(ZoneWand.ITEM)), "stored wand was not equipped");
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setItemInHand(InteractionHand.OFF_HAND, player.getMainHandItem());
            player.setItemInHand(InteractionHand.MAIN_HAND, player.getInventory().removeItemNoUpdate(1));
            player.inventoryMenu.broadcastChanges();
        });
        command(context, "zone wand");
        check(context.computeOnClient(mc -> mc.player.getMainHandItem().is(ZoneWand.ITEM)), "offhand wand was not equipped");
        check(itemCount(world, ZoneWand.ITEM) == 1 && itemCount(world, Items.GOLD_INGOT) == 63,
                "re-equipping a stored wand lost an item");

        BlockPos first = origin.offset(-2, -1, -2);
        BlockPos second = origin.offset(2, -1, 2);
        world.getServer().runOnServer(server -> server.overworld().setBlock(second,
                Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS));
        lookDownAt(context, world, name, first);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        context.waitTicks(10);
        check(state(world, first).is(Blocks.STONE), "left-clicking with the wand broke the selected block");
        lookDownAt(context, world, name, second);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        context.waitTicks(10);
        check(context.computeOnClient(mc -> mc.screen == null), "right-clicking with the wand opened a container");
        check(state(world, second).is(Blocks.CHEST), "right-clicking with the wand changed the selected block");
        context.runOnClient(mc -> mc.player.setXRot(35));
        context.waitTicks(5);
        context.takeScreenshot("world-held-zone-wand");
        check(calls.isEmpty(), "getting or using the wand called the AI");
    }

    private static void lookDownAt(ClientGameTestContext context, TestSingleplayerContext world, String name, BlockPos pos) {
        world.getServer().runCommand("tp " + name + " " + (pos.getX() + 0.5) + " " + (pos.getY() + 1)
                + " " + (pos.getZ() + 0.5) + " 0 90");
        context.waitTicks(10);
        context.waitFor(mc -> mc.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(pos));
    }

    private static int itemCount(TestSingleplayerContext world, Item item) {
        return world.getServer().computeOnServer(server -> {
            var inventory = server.getPlayerList().getPlayers().getFirst().getInventory();
            int count = 0;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                var stack = inventory.getItem(i);
                if (stack.is(item)) {
                    count += stack.getCount();
                }
            }
            return count;
        });
    }

    private static void zone(ClientGameTestContext context, BlockPos a, BlockPos b) {
        command(context, "zone 1 " + a.getX() + " " + a.getY() + " " + a.getZ());
        command(context, "zone 2 " + b.getX() + " " + b.getY() + " " + b.getZ());
    }

    private static void command(ClientGameTestContext context, String text) {
        context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + text));
        context.waitTicks(10);
    }

    private void awaitCalls(ClientGameTestContext context, int count) {
        for (int i = 0; i < 100 && calls.size() < count; i++) {
            context.waitTicks(5);
        }
        check(calls.size() >= count, "expected at least " + count + " AI calls, got " + calls.size());
        // Quick mode may add one diagnostic review, whose stub response declines and preserves the draft.
        for (int attempt = 0; attempt < 10; attempt++) {
            int previous = calls.size(); context.waitTicks(20);
            check(calls.size() <= count + 1, "unexpected extra review calls");
            if (calls.size() == previous) return;
        }
        throw new AssertionError("AI calls did not settle within ten seconds");
    }

    private static void awaitBlock(ClientGameTestContext context, TestSingleplayerContext world, BlockPos pos, Block block) {
        for (int i = 0; i < 100 && !state(world, pos).is(block); i++) {
            context.waitTicks(5);
        }
        check(state(world, pos).is(block), "expected " + block + " at " + pos + ", got " + state(world, pos));
        context.waitTicks(5);
    }

    private static BlockState state(TestSingleplayerContext world, BlockPos pos) {
        return world.getServer().computeOnServer(s -> s.overworld().getBlockState(pos));
    }

    private static int saved(TestSingleplayerContext world) {
        return world.getServer().computeOnServer(s -> JustBuildMod.manager().savedBuildCount());
    }

    private static String plan(String... operations) {
        return "{\"kind\":\"build\",\"title\":\"World tools test\",\"summary\":\"\",\"operations\":["
                + String.join(",", operations) + "]}";
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    private HttpServer startStub() {
        try {
            HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.createContext("/v1/chat/completions", exchange -> {
                JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                JsonArray messages = body.getAsJsonArray("messages");
                calls.add(new String[] {messages.get(0).getAsJsonObject().get("content").getAsString(),
                        messages.get(1).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()});
                String response = replies.poll();
                JsonObject message = new JsonObject();
                message.addProperty("content", response == null ? "{\"kind\":\"decline\",\"summary\":\"No test reply\"}" : response);
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
            stub.start();
            return stub;
        } catch (IOException e) {
            throw new AssertionError("Could not start stub AI", e);
        }
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
            config.confirmUndo = true;
            store.save(config);
        } catch (Exception e) {
            throw new AssertionError("Could not write test config", e);
        }
    }
}
