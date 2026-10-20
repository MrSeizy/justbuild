package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.JustBuildMod;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import dev.justbuild.net.PreviewPayload;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** The controls floating at a preview: right-click one to switch options, move, rotate, and build, without a tool. */
public final class PreviewControlsGameTest implements FabricClientGameTest {
    private static final String GOLD = plan("gold_block");
    private static final String LAPIS = plan("lapis_block");

    private static String plan(String block) {
        return "{\"kind\":\"build\",\"title\":\"Marker\",\"summary\":\"\",\"operations\":[{\"op\":\"fill\",\"block\":\""
                + block + "\",\"from\":[-1,0,0],\"to\":[1,0,0]}]}";
    }

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
            config.previewAbove = 0;
            config.detail = "quick";
            config.creativeDirection = false;
            new ConfigStore(file).save(config);
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                world.getClientLevel().waitForChunksRender();
                BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition().above(10));
                String player = context.computeOnClient(mc -> mc.player.getName().getString());
                world.getServer().runOnServer(server -> {
                    for (BlockPos pos : BlockPos.betweenClosed(at.offset(-8, -1, -8), at.offset(8, 8, 8))) {
                        server.overworld().setBlock(pos, pos.getY() == at.getY() - 1 ? Blocks.STONE.defaultBlockState()
                                : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                });
                world.getServer().runCommand("gamemode creative " + player);
                world.getServer().runCommand("tp " + player + " " + (at.getX() + 0.5) + " " + at.getY() + " "
                        + (at.getZ() + 3.0) + " 180 10");
                world.getServer().runCommand("item replace entity " + player + " weapon.mainhand with minecraft:oak_planks");
                context.waitTicks(20);
                command(context, "zone 1 " + position(at.offset(-3, -1, -3)));
                command(context, "zone 2 " + position(at.offset(3, -1, 3)));
                command(context, "alternatives build 2 a marker");
                context.waitFor(mc -> ClientState.preview() != null && ClientState.preview().options().size() == 2, 1200);
                context.waitTicks(10);
                checkBaseLayout(context);
                context.takeScreenshot("preview-world-controls");
                // The panel offers Checks and Floors while a preview waits (Preview tools is gone).
                context.runOnClient(mc -> mc.setScreen(new JustBuildScreen()));
                context.tryClickScreenButton("Create");
                context.waitTicks(5);
                context.takeScreenshot("glass-create-preview-waiting");
                check(context.computeOnClient(mc -> ClientState.preview().added() == 3 && ClientState.preview().replaced() == 0
                        && ClientState.preview().removed() == 0 && ClientState.preview().width() == 3), "preview summary counts or dimensions wrong");
                context.tryClickScreenButton("Before");
                check(context.computeOnClient(mc -> GhostPreview.before()), "Before did not change the view");
                context.tryClickScreenButton("After");
                context.tryClickScreenButton("Show: all");
                check(context.computeOnClient(mc -> GhostPreview.filter() == GhostPreview.Filter.ADDED), "change filter did not advance");
                context.tryClickScreenButton("2 · Marker");
                context.waitFor(mc -> ClientState.preview().option() == 1, 200);
                context.tryClickScreenButton("1 · Marker");
                context.waitFor(mc -> ClientState.preview().option() == 0, 200);
                context.runOnClient(mc -> {
                    mc.screen.resize(320, 240);
                    for (var child : mc.screen.children()) if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget)
                        check(widget.getX() >= 0 && widget.getRight() <= 320 && widget.getY() >= 0 && widget.getBottom() <= 240,
                                "preview control outside narrow screen: " + widget.getMessage().getString());
                    var before = mc.screen.children().stream().filter(c -> c instanceof GlassButton b && b.getMessage().getString().equals("Before"))
                            .findFirst().orElseThrow();
                    mc.screen.setFocused(before);
                    mc.screen.keyPressed(new net.minecraft.client.input.KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
                    check(GhostPreview.before(), "keyboard could not activate Before");
                    GhostPreview.before(false); GhostPreview.filter(GhostPreview.Filter.ALL);
                });
                context.takeScreenshot("preview-summary-narrow");
                check(context.computeOnClient(mc -> mc.screen.children().stream().anyMatch(c -> c instanceof GlassButton b
                        && b.getMessage().getString().equals("Floors"))), "the Create tab has no Floors button while a preview waits");
                check(context.computeOnClient(mc -> mc.screen.children().stream().noneMatch(c -> c instanceof net.minecraft.client.gui.components.Button b
                        && b.getMessage().getString().equals("Preview tools"))), "the Create tab still has Preview tools");
                context.runOnClient(mc -> mc.setScreen(null));
                context.waitTicks(3);
                // The whole layout from a few steps back, without chat in the way.
                world.getServer().runCommand("tp " + player + " " + (at.getX() + 0.5) + " " + at.getY() + " "
                        + (at.getZ() + 7.5) + " 180 0");
                context.waitTicks(5);
                runOnLook(context, Vec3.atCenterOf(at).add(0, 1.5, 0));
                context.runOnClient(mc -> mc.options.hideGui = true);
                context.waitTicks(3);
                context.takeScreenshot("preview-world-controls-overview");
                context.runOnClient(mc -> mc.options.hideGui = false);
                world.getServer().runCommand("tp " + player + " " + (at.getX() + 0.5) + " " + at.getY() + " "
                        + (at.getZ() + 3.0) + " 180 10");
                context.waitTicks(5);

                // The state packet may arrive before the next control layout. A stale option arrow must not divide
                // by zero or send an option command when the replacement preview has no alternatives.
                aim(context, PreviewControls.Control.NEXT);
                context.runOnClient(mc -> {
                    ClientState.update("{\"preview\":{\"title\":\"Replacement\",\"movable\":true,\"quality\":[],"
                            + "\"options\":[],\"option\":0,\"position\":\"replacement\"}}");
                    KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_RIGHT));
                    PreviewControls.beforeTick(mc);
                    check(!mc.options.keyUse.consumeClick(), "the stale arrow did not consume its click");
                });
                world.getServer().runOnServer(server -> JustBuildMod.manager().sendState(server.getPlayerList().getPlayers().getFirst()));
                context.waitFor(mc -> ClientState.preview() != null && ClientState.preview().options().size() == 2, 200);
                context.waitTicks(3);
                check(context.computeOnClient(mc -> ClientState.preview().option() == 0), "the stale arrow changed the selected option");

                // A click on a control is the control's: the plank in hand isn't placed against the block behind it.
                Vec3 next = aim(context, PreviewControls.Control.NEXT);
                // Within reach, so an ordinary right-click here would place the plank against it.
                BlockPos behind = BlockPos.containing(next.add(next.subtract(eye(context)).normalize().scale(0.8)));
                world.getServer().runOnServer(s -> s.overworld().setBlock(behind, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS));
                context.waitTicks(3);
                int planks = planks(world, at);
                click(context);
                try { context.waitFor(mc -> ClientState.preview().option() == 1, 200); }
                catch (AssertionError e) { throw new AssertionError("the arrow did not switch to option 2", e); }
                aim(context, PreviewControls.Control.PREVIOUS);
                click(context);
                try { context.waitFor(mc -> ClientState.preview().option() == 0, 200); }
                catch (AssertionError e) { throw new AssertionError("the previous arrow did not go back to option 1", e); }
                aim(context, PreviewControls.Control.NEXT);
                click(context);
                try { context.waitFor(mc -> ClientState.preview().option() == 1, 200); }
                catch (AssertionError e) { throw new AssertionError("the arrow did not switch to option 2 again", e); }
                context.waitTicks(10);
                check(planks(world, at) == planks, "the click on a control also placed a block");

                // Anywhere else, right-click works as usual.
                runOnLook(context, Vec3.atCenterOf(at.offset(-3, -1, 2)).add(0, 0.5, 0));
                click(context);
                context.waitTicks(10);
                check(planks(world, at) == planks + 1, "a right-click away from the controls didn't place the block");

                // From a few steps back: use all four arrows in the D-pad, rotate, then build
                // the shown option.
                world.getServer().runCommand("tp " + player + " " + (at.getX() + 0.5) + " " + at.getY() + " "
                        + (at.getZ() + 6.5) + " 180 10");
                context.waitTicks(5);
                BlockPos east = press(context, PreviewControls.Control.EAST);
                check(east.equals(at.east()), "the east arrow moved the preview to " + east);
                BlockPos away = press(context, PreviewControls.Control.NORTH);
                check(away.equals(at.east().north()), "the away arrow moved the preview to " + away);
                BlockPos west = press(context, PreviewControls.Control.WEST);
                check(west.equals(at.north()), "the left arrow moved the preview to " + west);
                BlockPos toward = press(context, PreviewControls.Control.SOUTH);
                check(toward.equals(at), "the toward arrow moved the preview to " + toward);
                press(context, PreviewControls.Control.EAST);
                press(context, PreviewControls.Control.NORTH);
                BlockPos raised = press(context, PreviewControls.Control.UP);
                check(raised.equals(away.above()), "Up moved the preview to " + raised);
                raised = press(context, PreviewControls.Control.UP);
                check(raised.equals(away.above(2)), "a second Up moved the preview to " + raised);
                BlockPos snapped = press(context, PreviewControls.Control.SNAP);
                check(snapped.equals(away), "Snap left the preview at " + snapped);
                String moved = context.computeOnClient(mc -> ClientState.preview().position());
                aim(context, PreviewControls.Control.ROTATE);
                click(context);
                context.waitFor(mc -> !ClientState.preview().position().equals(moved), 200);
                context.waitTicks(5);
                context.takeScreenshot("preview-world-controls-moved");
                aim(context, PreviewControls.Control.BUILD);
                click(context);
                context.waitFor(mc -> ClientState.preview() == null, 200);
                context.waitTicks(20);
                check(world.getServer().computeOnServer(s -> JustBuildMod.manager().savedBuildCount()) == 1, "Build it did not build");
                check(world.getServer().computeOnServer(s -> s.overworld().getBlockState(away).is(Blocks.LAPIS_BLOCK)),
                        "the build is not the chosen option at the moved spot");

                // A delayed Build/Discard click must not confirm or cancel a different kind of action.
                command(context, "undo");
                command(context, "confirm");
                command(context, "preview discard");
                check(world.getServer().computeOnServer(s -> s.overworld().getBlockState(away).is(Blocks.LAPIS_BLOCK)),
                        "a stale Build click confirmed an undo");
                check(context.computeOnClient(mc -> ClientState.undoWaiting() != null), "stale Discard cancelled an undo");
                command(context, "undo confirm");
                context.waitTicks(20);
                check(world.getServer().computeOnServer(s -> JustBuildMod.manager().savedBuildCount()) == 0,
                        "explicit undo confirmation failed");
            }
        } catch (Exception e) {
            throw new AssertionError("Could not exercise the in-world preview controls", e);
        } finally {
            stub.stop(0);
            try {
                if (original == null) Files.deleteIfExists(file);
                else Files.write(file, original);
            } catch (Exception e) { throw new AssertionError("Could not restore test config", e); }
        }
    }

    /** A taller and wider preview must not lift controls to its roof or scatter the D-pad to its edges. */
    private static void checkBaseLayout(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            var box = PreviewBox.area(mc);
            String dimension = mc.level.dimension().identifier().toString(), title = ClientState.preview().title();
            var original = new PreviewPayload(true, dimension, BlockPos.containing(box.minX, box.minY, box.minZ),
                    BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1), title);
            try {
                PreviewBox.receive(new PreviewPayload(true, dimension, original.min().offset(-10, 0, -10),
                        original.max().offset(10, 30, 10), title));
                PreviewControls.tick(mc);
                for (var control : PreviewControls.Control.values()) {
                    Vec3 p = PreviewControls.position(control);
                    check(p != null && p.y > box.minY && p.y < box.minY + 4,
                            control + " is not at the preview base: " + p);
                }
                var directions = List.of(PreviewControls.Control.NORTH, PreviewControls.Control.SOUTH,
                        PreviewControls.Control.EAST, PreviewControls.Control.WEST);
                for (var a : directions) for (var b : directions) {
                    check(PreviewControls.position(a).distanceTo(PreviewControls.position(b)) < 1.5,
                            "movement arrows are not grouped together");
                }
            } finally {
                PreviewBox.receive(original);
                PreviewControls.tick(mc);
            }
        });
    }

    /** Looks at a control, waits until it is under the crosshair, and returns where it is. */
    static Vec3 aim(ClientGameTestContext context, PreviewControls.Control control) {
        Vec3 target = context.computeOnClient(mc -> PreviewControls.position(control));
        if (target == null) throw new AssertionError(control + " is not shown");
        runOnLook(context, target);
        try {
            context.waitFor(mc -> PreviewControls.hovered() == control, 100);
        } catch (AssertionError e) {
            String seen = context.computeOnClient(mc -> {
                var camera = mc.gameRenderer.getMainCamera();
                return "camera " + camera.position() + " forward " + camera.forwardVector() + " up " + camera.upVector()
                        + " player look " + mc.player.getViewVector(1f) + " target " + target + " hovered " + PreviewControls.hovered();
            });
            throw new AssertionError(control + " was not under the crosshair: " + seen, e);
        }
        return target;
    }

    /** Clicks a move control (arrow, Up, Down, or Snap) and returns where the preview's origin went. */
    private static BlockPos press(ClientGameTestContext context, PreviewControls.Control control) {
        String before = context.computeOnClient(mc -> ClientState.preview().position());
        aim(context, control);
        click(context);
        context.waitFor(mc -> !ClientState.preview().position().equals(before), 200);
        context.waitTicks(5);  // the controls follow the moved preview on the next layout
        String[] xyz = context.computeOnClient(mc -> ClientState.preview().position()).split(" · ")[0].split(", ");
        return new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
    }

    /** A right-click as a player makes one: the button stays down for a few ticks, not just a press event. */
    static void click(ClientGameTestContext context) {
        context.getInput().holdMouseFor(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 3);
    }

    static void runOnLook(ClientGameTestContext context, Vec3 target) {
        context.runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, target));
        context.waitTicks(3);
    }

    private static Vec3 eye(ClientGameTestContext context) {
        return context.computeOnClient(mc -> mc.player.getEyePosition());
    }

    private static int planks(TestSingleplayerContext world, BlockPos at) {
        return world.getServer().computeOnServer(s -> {
            int n = 0;
            for (BlockPos pos : BlockPos.betweenClosed(at.offset(-8, -1, -8), at.offset(8, 8, 8))) {
                if (s.overworld().getBlockState(pos).is(Blocks.OAK_PLANKS)) n++;
            }
            return n;
        });
    }

    private static HttpServer stub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger calls = new AtomicInteger();
            server.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                JsonObject message = new JsonObject(); message.addProperty("content", calls.getAndIncrement() % 2 == 0 ? GOLD : LAPIS);
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
