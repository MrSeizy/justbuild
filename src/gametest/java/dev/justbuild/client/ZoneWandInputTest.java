package dev.justbuild.client;

import dev.justbuild.world.BuildRegion;
import dev.justbuild.world.ZoneWand;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;

/** Real held clicks must select once, and releasing the button must allow another selection. */
public final class ZoneWandInputTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        List<String> messages = new ArrayList<>();
        AtomicBoolean recording = new AtomicBoolean(true);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (recording.get() && !overlay) messages.add(message.getString());
        });
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            String name = context.computeOnClient(mc -> mc.player.getName().getString());
            BlockPos first = context.computeOnClient(mc -> mc.player.blockPosition().above(10));
            BlockPos second = first.east(2);
            BlockPos roof = first.offset(1, 5, 0);
            BlockPos edge = first.offset(-2, 2, 3);
            world.getServer().runOnServer(server -> {
                server.overworld().setBlock(first, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                server.overworld().setBlock(second, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
                server.overworld().setBlock(roof, Blocks.AMETHYST_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                server.overworld().setBlock(edge, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            });
            for (String mode : List.of("creative", "survival")) {
                world.getServer().runCommand("gamemode " + mode + " " + name);
                command(context, "zone wand");
                lookAt(context, world, name, first);
                clickOnce(context, messages, GLFW.GLFW_MOUSE_BUTTON_LEFT, "Zone corner 1:", mode);
                // Repeating a deliberate click on the same block is still a valid new selection.
                clickOnce(context, messages, GLFW.GLFW_MOUSE_BUTTON_LEFT, "Zone corner 1:", mode + " second press");
                lookAt(context, world, name, second);
                clickOnce(context, messages, GLFW.GLFW_MOUSE_BUTTON_RIGHT, "Build zone:", mode);
                check(context.computeOnClient(mc -> ClientState.string("zoneLabel", "").startsWith("3 × 1 blocks")),
                        mode + ": held clicks did not select the expected footprint");
                check(context.computeOnClient(mc -> mc.screen == null), "wand opened a chest");
            }
            for (String mode : List.of("creative", "survival")) {
                world.getServer().runCommand("gamemode " + mode + " " + name);
                long zoneMessages = count(context, messages, "Builder Wand · Zone mode equipped");
                context.runOnClient(mc -> mc.setScreen(new AddExistingScreen(new WorkshopScreen(new JustBuildScreen()))));
                context.clickScreenButton("Wand: Box");
                context.waitFor(mc -> mc.player.getMainHandItem().is(ZoneWand.ITEM) && ZoneWand.isPart(mc.player.getMainHandItem()));
                check(context.computeOnClient(mc -> mc.player.getMainHandItem().getHoverName().getString().equals("Builder Wand · Box")),
                        "the button did not equip the named Builder Wand in Box mode");
                context.runOnClient(mc -> mc.setScreen(new EditingToolsScreen(new JustBuildScreen())));
                context.clickScreenButton("Wand: Box");
                context.waitTicks(10);
                check(context.computeOnClient(mc -> mc.player.getMainHandItem().is(ZoneWand.ITEM) && ZoneWand.isPart(mc.player.getMainHandItem())
                        && mc.player.getInventory().getNonEquipmentItems().stream().filter(s -> ZoneWand.isWand(s)).count() == 1),
                        "the editing tools button did not reuse the existing Builder Wand");
                check(count(context, messages, "Builder Wand · Zone mode equipped") == zoneMessages, "part wand reported zone controls");

                lookAt(context, world, name, first);
                clickOnce(context, messages, GLFW.GLFW_MOUSE_BUTTON_LEFT, "Box corner 1:", mode + " part start");
                check(context.computeOnClient(mc -> ClientState.part() == null && ClientState.flag("selectingPart")
                        && ClientState.partOutline().min().equals(first) && ClientState.partOutline().max().equals(first)),
                        "first point did not show an incomplete selection outline");
                context.runOnClient(mc -> mc.setScreen(new AddExistingScreen(new JustBuildScreen())));
                check(context.computeOnClient(mc -> mc.screen.children().stream()
                        .filter(w -> w instanceof net.minecraft.client.gui.components.Button b
                                && b.getMessage().getString().equals("Add build and save original"))
                        .allMatch(w -> !((net.minecraft.client.gui.components.Button) w).active)),
                        "adoption enabled for only a first point");
                context.runOnClient(mc -> mc.setScreen(null));
                lookAt(context, world, name, second);
                clickOnce(context, messages, GLFW.GLFW_MOUSE_BUTTON_RIGHT, "Box:", mode + " footprint");
                assertPart(context, first, second, "footprint");
                lookAt(context, world, name, roof);
                clickOnce(context, messages, GLFW.GLFW_MOUSE_BUTTON_RIGHT, "Box:", mode + " roof");
                assertPart(context, first, second.above(5), "roof preserved footprint and bottom");
                lookAt(context, world, name, edge);
                clickOnce(context, messages, GLFW.GLFW_MOUSE_BUTTON_RIGHT, "Box:", mode + " extra edge");
                assertPart(context, first.west(2), second.offset(0, 5, 3), "fourth point preserved roof and expanded footprint");
                lookAt(context, world, name, second);
                clickOnce(context, messages, GLFW.GLFW_MOUSE_BUTTON_RIGHT, "Box:", mode + " interior point");
                assertPart(context, first.west(2), second.offset(0, 5, 3), "interior point did not shrink selection");
                command(context, "box 2 " + first.offset(256, 0, 0).getX() + " " + first.getY() + " " + first.getZ());
                assertPart(context, first.west(2), second.offset(0, 5, 3), "rejected expansion preserved selection");

                // Selecting Zone mode must preserve the independent part selection.
                command(context, "zone wand");
                clickOnce(context, messages, GLFW.GLFW_MOUSE_BUTTON_LEFT, "Zone corner 1:", mode + " zone item routing");
                assertPart(context, first.west(2), second.offset(0, 5, 3), "zone wand changed part selection");
                command(context, "box wand");
                // Clearing a selection must not change the mode stored on the wand.
                command(context, "select clear");
                clickOnce(context, messages, GLFW.GLFW_MOUSE_BUTTON_LEFT, "Box corner 1:", mode + " reset");
                check(context.computeOnClient(mc -> ClientState.part() == null && ClientState.partOutline().min().equals(second)),
                        "left-click did not restart at the new point");
                lookAt(context, world, name, first);
                clickOnce(context, messages, GLFW.GLFW_MOUSE_BUTTON_RIGHT, "Box:", mode + " reset finish");
                lookAt(context, world, name, roof);
                clickOnce(context, messages, GLFW.GLFW_MOUSE_BUTTON_RIGHT, "Box:", mode + " reset roof");
                assertPart(context, first, second.above(5), "restart discarded old extra edge");
            }

            context.getInput().holdShift();
            context.waitTicks(2);
            clickOnce(context, messages, GLFW.GLFW_MOUSE_BUTTON_RIGHT, "That block is not part of a saved AI build.",
                    "sneak selection");
            context.getInput().releaseShift();
            check(world.getServer().computeOnServer(server -> server.overworld().getBlockState(first).is(Blocks.STONE)
                    && server.overworld().getBlockState(second).is(Blocks.CHEST)
                    && server.overworld().getBlockState(roof).is(Blocks.AMETHYST_BLOCK)
                    && server.overworld().getBlockState(edge).is(Blocks.STONE)), "wand changed selected blocks");
            world.getServer().runCommand("gamemode creative " + name);
            world.getServer().runOnServer(server -> server.overworld().setBlock(first.offset(7, 4, 9),
                    Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS));
            world.getServer().runCommand("tp " + name + " " + (first.getX() + 7.5) + " " + (first.getY() + 5)
                    + " " + (first.getZ() + 9.5) + " 145 22");
            context.waitTicks(15);
            command(context, "box");
            context.runOnClient(mc -> mc.gui.getChat().clearMessages(false));
            context.waitTicks(2);
            context.takeScreenshot("part-wand-extend-outline");
            context.runOnClient(mc -> mc.setScreen(new AddExistingScreen(new JustBuildScreen())));
            context.takeScreenshot("part-wand-adopt-controls");
            context.runOnClient(mc -> mc.setScreen(new EditingToolsScreen(new JustBuildScreen())));
            context.takeScreenshot("part-wand-editing-controls");
        } finally {
            recording.set(false);
        }
    }

    private static void assertPart(ClientGameTestContext context, BlockPos min, BlockPos max, String scenario) {
        check(context.computeOnClient(mc -> BuildRegion.between(mc.level.dimension(), min, max).equals(ClientState.part())
                && ClientState.part().equals(ClientState.partOutline()) && !ClientState.flag("selectingPart")), scenario);
    }

    private static void clickOnce(ClientGameTestContext context, List<String> messages, int button,
            String expected, String scenario) {
        long before = count(context, messages, expected);
        context.getInput().holdMouseFor(button, 20);
        context.waitTicks(3);
        long added = count(context, messages, expected) - before;
        check(added == 1, scenario + ": one held click printed " + added + " messages containing " + expected);
    }

    private static long count(ClientGameTestContext context, List<String> messages, String expected) {
        return context.computeOnClient(mc -> messages.stream().filter(line -> line.contains(expected)).count());
    }

    private static void lookAt(ClientGameTestContext context, TestSingleplayerContext world, String name, BlockPos pos) {
        world.getServer().runCommand("tp " + name + " " + (pos.getX() + 0.5) + " " + (pos.getY() + 1)
                + " " + (pos.getZ() + 0.5) + " 0 90");
        context.waitTicks(10);
        context.waitFor(mc -> mc.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(pos));
    }

    private static void command(ClientGameTestContext context, String text) {
        context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + text));
        context.waitTicks(10);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
