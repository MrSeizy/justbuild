package dev.justbuild.client;

import dev.justbuild.JustBuildMod;
import dev.justbuild.command.JustBuildCommand;
import dev.justbuild.world.ZoneWand;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** The held item must not bypass command permission, reach checks, or the requirement to click an actual block. */
public final class ZoneWandPermissionsTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestDedicatedServerContext server = context.worldBuilder().createServer();
                TestServerConnection connection = server.connect()) {
            connection.getClientLevel().waitForChunksRender();
            String name = context.computeOnClient(mc -> mc.player.getName().getString());
            // Start the denial checks with a non-operator, independent of earlier tests' permission changes.
            server.runCommand("deop " + name);
            context.waitTicks(10);
            BlockPos ground = context.computeOnClient(mc -> mc.player.blockPosition().below());
            server.runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                check(!JustBuildCommand.allowed(player.createCommandSourceStack()), "test player unexpectedly has permission");
                check(!ZoneWand.giveStarter(player), "non-operator received an automatic wand");
                for (var wand : List.of(ZoneWand.ITEM, ZoneWand.PART_ITEM)) {
                    check(!ZoneWand.give(player.createCommandSourceStack(), wand == ZoneWand.PART_ITEM),
                            "non-operator received a wand through the direct action");
                    // Simulate a wand obtained from another player. Owning the item does not grant permission.
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(wand));
                    player.setShiftKeyDown(true);
                    UseItemCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND);
                    check(ZoneWand.isPart(player.getMainHandItem()) == (wand == ZoneWand.PART_ITEM),
                            "non-operator switched wand modes");
                    player.setShiftKeyDown(false);
                    AttackBlockCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND,
                            ground, Direction.UP);
                    UseBlockCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(ground), Direction.UP, ground, false));
                }
                JustBuildMod.manager().sendState(player);
            });
            context.waitTicks(10);
            assertNoSelection(context, "a non-operator selected a zone with the wand");
            check(context.computeOnClient(mc -> ClientState.lines().stream()
                    .anyMatch(line -> line.getString().contains("You need permission"))), "permission refusal was not shown");

            // Extended input uses the existing selection commands, which must reject the same non-operator.
            PreviewControlsGameTest.runOnLook(context,
                    new Vec3(ground.getX() + 20.5, ground.getY() + 1, ground.getZ() + 0.5));
            context.waitFor(mc -> ZoneWandInput.target(mc) != null
                    && ZoneWandInput.target(mc).getLocation().distanceToSqr(mc.player.getEyePosition()) > 100);
            context.getInput().holdMouseFor(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT, 20);
            context.getInput().holdMouseFor(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT, 20);
            context.waitTicks(5);
            assertNoSelection(context, "a non-operator selected a distant part through the extended input path");

            server.runCommand("op " + name);
            context.waitTicks(10);
            server.runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                check(JustBuildCommand.allowed(player.createCommandSourceStack()), "operator permission was not granted");
                for (var wand : List.of(ZoneWand.ITEM, ZoneWand.PART_ITEM)) {
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(wand));
                    AttackBlockCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND,
                            ground.offset(100, 0, 0), Direction.UP);
                    AttackBlockCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND,
                            ground.above(2), Direction.UP);
                }
                JustBuildMod.manager().sendState(player);
            });
            context.waitTicks(10);
            assertNoSelection(context, "a forged remote or air click selected a zone");

            server.runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ZoneWand.ITEM));
                AttackBlockCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND,
                        ground, Direction.UP);
                UseBlockCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(ground.east()), Direction.UP, ground.east(), false));
            });
            context.waitTicks(10);
            check(context.computeOnClient(mc -> ClientState.string("zoneLabel", "").startsWith("2 × 1 blocks")),
                    "an operator's in-range clicks did not select the footprint");

            context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild box wand")); context.waitTicks(5);
            context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild box 1")); context.waitTicks(5);
            context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild box 2")); context.waitTicks(5);
            var selected = context.computeOnClient(mc -> ClientState.part());
            check(selected != null, "permission fixture did not select a part");
            server.runCommand("deop " + name); context.waitTicks(5);
            context.getInput().holdShift();
            try {
                context.waitTicks(2);
                context.getInput().holdMouseFor(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT, 20);
            } finally { context.getInput().releaseShift(); }
            context.waitTicks(5);
            check(context.computeOnClient(mc -> selected.equals(ClientState.part())),
                    "the clear shortcut bypassed command permissions");
        }
    }

    private static void assertNoSelection(ClientGameTestContext context, String message) {
        check(context.computeOnClient(mc -> ClientState.string("zoneLabel", null) == null
                && !ClientState.flag("selectingZone") && !ClientState.flag("selectingPart") && ClientState.part() == null), message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
