package dev.justbuild.client;

import dev.justbuild.JustBuildMod;
import dev.justbuild.world.ZoneWand;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.mojang.blaze3d.platform.InputConstants;

/** Automatic delivery, legacy inventory conversion, and real held-input mode changes. */
public final class BuilderWandGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        List<String> messages = new ArrayList<>();
        AtomicBoolean recording = new AtomicBoolean(true);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (recording.get() && overlay) messages.add(message.getString());
        });
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            context.waitFor(mc -> mc.player.getInventory().getNonEquipmentItems().stream().filter(ZoneWand::isWand).count() == 1);
            check(count(world) == 1, "joining did not automatically give exactly one Builder Wand");
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                JustBuildMod.manager().giveStarterWand(player);
                JustBuildMod.manager().giveStarterWand(player);
            });
            check(count(world) == 1, "repeated delivery duplicated the wand");

            // The two old item IDs still load and combine without overwriting unrelated stacks.
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                var inventory = player.getInventory();
                inventory.clearContent();
                inventory.setSelectedSlot(2);
                inventory.setItem(2, new ItemStack(Items.DIAMOND, 17));
                inventory.setItem(12, new ItemStack(ZoneWand.ITEM));
                player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ZoneWand.PART_ITEM));
                check(ZoneWand.giveStarter(player), "old selectors could not be converted");
                check(inventory.getSelectedSlot() == 2 && player.getMainHandItem().is(Items.DIAMOND)
                        && player.getMainHandItem().getCount() == 17, "automatic delivery changed the held item");
                check(player.getOffhandItem().is(ZoneWand.ITEM) && ZoneWand.isPart(player.getOffhandItem()),
                        "legacy offhand Part Wand lost its mode");
                check(inventory.getNonEquipmentItems().stream().noneMatch(ZoneWand::isWand),
                        "legacy zone wand was not consolidated into the offhand tool");
            });
            command(context, "box wand");
            assertMode(context, true);
            check(count(world) == 1, "equipping the converted wand duplicated it");

            // A full inventory delays delivery; freeing a slot lets the normal tick finish it.
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                var inventory = player.getInventory();
                inventory.clearContent();
                player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                for (int i = 0; i < inventory.getNonEquipmentItems().size(); i++) {
                    inventory.setItem(i, new ItemStack(Items.GOLD_INGOT, 64));
                }
                JustBuildMod.manager().playerRespawned(player);
                check(inventory.getNonEquipmentItems().stream().allMatch(s -> s.is(Items.GOLD_INGOT) && s.getCount() == 64),
                        "automatic delivery overwrote a full inventory");
            });
            context.waitTicks(25);
            check(count(world) == 0, "full inventory unexpectedly received a wand");
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.getInventory().setItem(12, ItemStack.EMPTY);
            });
            context.waitFor(mc -> mc.player.getInventory().getNonEquipmentItems().stream().anyMatch(ZoneWand::isWand));
            check(count(world) == 1, "delivery did not retry after inventory space became available");
            command(context, "zone wand");
            assertMode(context, false);

            String name = context.computeOnClient(mc -> mc.player.getName().getString());
            world.getServer().runCommand("gamemode creative " + name);
            world.getServer().runCommand("tp " + name + " ~ ~ ~ 0 -75");
            context.waitTicks(10);
            for (String mode : List.of("creative", "survival")) {
                world.getServer().runCommand("gamemode " + mode + " " + name);
                context.getInput().holdShift();
                context.waitTicks(2);
                switchOnce(context, messages, true, mode);
                switchOnce(context, messages, false, mode);
                context.getInput().releaseShift();
                context.waitTicks(3);
            }
            context.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
            context.takeScreenshot("builder-wand-zone-gold");
            context.getInput().holdShift();
            context.waitTicks(2);
            switchOnce(context, messages, true, "screenshot");
            context.getInput().releaseShift();
            context.waitTicks(3);
            context.takeScreenshot("builder-wand-part-purple");
            // No automatic loop replenishes a deliberately removed tool during the same life/session.
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            });
            context.waitTicks(30);
            check(count(world) == 0, "automatic delivery kept replacing a deliberately removed wand");
        } finally {
            recording.set(false);
        }
    }

    private static void switchOnce(ClientGameTestContext context, List<String> messages, boolean part, String mode) {
        long before = context.computeOnClient(mc -> messages.stream().filter(s -> s.startsWith("Builder Wand ·")).count());
        context.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_RIGHT, 20);
        context.waitTicks(3);
        long after = context.computeOnClient(mc -> messages.stream().filter(s -> s.startsWith("Builder Wand ·")).count());
        check(after - before == 1, mode + ": one held click switched modes " + (after - before) + " times");
        assertMode(context, part);
    }

    private static void assertMode(ClientGameTestContext context, boolean part) {
        check(context.computeOnClient(mc -> mc.player.getMainHandItem().is(ZoneWand.ITEM)
                && ZoneWand.isPart(mc.player.getMainHandItem()) == part
                && mc.player.getMainHandItem().getHoverName().getString().equals("Builder Wand · " + (part ? "Box" : "Zone"))
                && Identifier.fromNamespaceAndPath("justbuild", part ? "box_wand" : "zone_wand")
                        .equals(mc.player.getMainHandItem().get(DataComponents.ITEM_MODEL))),
                "wand mode, displayed name, and color model disagree");
    }

    private static int count(TestSingleplayerContext world) {
        return world.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            return (int) player.getInventory().getNonEquipmentItems().stream().filter(ZoneWand::isWand).count()
                    + (ZoneWand.isWand(player.getOffhandItem()) ? 1 : 0);
        });
    }

    private static void command(ClientGameTestContext context, String command) {
        context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + command));
        context.waitTicks(10);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
