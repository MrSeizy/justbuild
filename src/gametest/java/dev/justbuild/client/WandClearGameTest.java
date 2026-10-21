package dev.justbuild.client;

import dev.justbuild.world.ZoneWand;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import com.mojang.blaze3d.platform.InputConstants;

/** Real sneak/attack input clears completed, incomplete, and moving selections without starting another. */
public final class WandClearGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext c) {
        List<String> messages = new ArrayList<>();
        AtomicBoolean recording = new AtomicBoolean(true);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (recording.get()) messages.add(message.getString());
        });
        try (TestSingleplayerContext w = c.worldBuilder().create()) {
            w.getConnection().waitForChunksRender();
            String name = c.computeOnClient(mc -> mc.player.getName().getString());
            BlockPos base = c.computeOnClient(mc -> mc.player.blockPosition().below());
            w.getServer().runOnServer(s -> s.overworld().setBlock(base, Blocks.BARREL.defaultBlockState(), Block.UPDATE_CLIENTS));
            command(c, "zone 1 " + pos(base.offset(-4, 0, -4)));
            command(c, "zone 2 " + pos(base.offset(4, 0, 4)));
            var zone = c.computeOnClient(mc -> ClientState.zone());
            command(c, "box wand");
            for (String mode : List.of("creative", "survival")) {
                w.getServer().runCommand("gamemode " + mode + " " + name);
                for (String aim : List.of("near", "far", "air")) {
                    select(c, base, true);
                    stand(c, w, name, base);
                    if (aim.equals("far")) {
                        PreviewControlsGameTest.runOnLook(c, net.minecraft.world.phys.Vec3.atCenterOf(base.east(24)).add(0, 0.5, 0));
                        check(c.computeOnClient(mc -> mc.hitResult.getType() == HitResult.Type.MISS && ZoneWandInput.target(mc) != null),
                                "far fixture was not outside normal reach");
                    } else if (aim.equals("air")) {
                        c.runOnClient(mc -> mc.player.setXRot(-90)); c.waitTicks(3);
                        check(c.computeOnClient(mc -> ZoneWandInput.target(mc) == null), "air fixture hit a block");
                    }
                    clearOnce(c, messages, mode + " " + aim);
                    check(c.computeOnClient(mc -> zone.equals(ClientState.zone()) && ZoneWand.isPart(mc.player.getMainHandItem())),
                            "clearing part changed the zone or wand mode");
                }
                select(c, base, false);
                stand(c, w, name, base);
                clearOnce(c, messages, mode + " incomplete selection");
                clearOnce(c, messages, mode + " already empty");
            }

            // A picked-up corner must not survive and recreate the cleared part on the next click.
            w.getServer().runCommand("gamemode creative " + name);
            select(c, base, true);
            w.getServer().runCommand("tp " + name + " " + (base.getX() + 0.5) + " " + (base.getY() + 1)
                    + " " + (base.getZ() + 8.5) + " 180 20");
            c.waitTicks(8);
            PreviewControlsGameTest.runOnLook(c, c.computeOnClient(mc -> SelectionHandles.partCornerPosition(ClientState.part(), 0)));
            c.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_RIGHT, 20);
            c.waitFor(mc -> SelectionHandles.moving());
            clearOnce(c, messages, "moving corner");
            check(c.computeOnClient(mc -> !SelectionHandles.moving()), "clearing left a corner picked up");

            // Zone mode retains normal sneak-attack selection behavior.
            select(c, base, true);
            var part = c.computeOnClient(mc -> ClientState.part());
            command(c, "zone wand"); stand(c, w, name, base);
            gesture(c);
            check(c.computeOnClient(mc -> part.equals(ClientState.part()) && ClientState.flag("selectingZone")),
                    "Zone mode cleared a part instead of starting a zone");

            // An offhand wand must not steal the main-hand tool's attack; an empty main hand can use the shortcut.
            w.getServer().runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
                player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ZoneWand.PART_ITEM));
                player.inventoryMenu.broadcastChanges();
            });
            c.waitTicks(5);
            c.runOnClient(mc -> mc.player.setXRot(-90)); c.waitTicks(3);
            gesture(c);
            check(c.computeOnClient(mc -> part.equals(ClientState.part())), "offhand wand stole the main-hand attack");
            w.getServer().runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                player.inventoryMenu.broadcastChanges();
            });
            c.waitTicks(5);
            clearOnce(c, messages, "offhand wand with empty main hand");
            check(w.getServer().computeOnServer(s -> s.overworld().getBlockState(base).is(Blocks.BARREL)),
                    "clear shortcut broke the targeted block");

            command(c, "box wand"); select(c, base, true); stand(c, w, name, base);
            c.runOnClient(mc -> { mc.gui.hud.getChat().clearMessages(false); mc.player.setXRot(20); });
            c.waitTicks(3); c.takeScreenshot("wand-clear-shortcut-hud");
            clearOnce(c, messages, "HUD example");
            c.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
            c.waitTicks(3); c.takeScreenshot("wand-cleared-part-hud");
        } finally { recording.set(false); }
    }
    private static void select(ClientGameTestContext c, BlockPos base, boolean complete) {
        command(c, "box 1 " + pos(base));
        if (complete) command(c, "box 2 " + pos(base.offset(4, 3, 4)));
        check(c.computeOnClient(mc -> ClientState.partOutline() != null && (ClientState.part() != null) == complete),
                "fixture did not select the part");
    }
    private static void stand(ClientGameTestContext c, TestSingleplayerContext w, String name, BlockPos base) {
        w.getServer().runCommand("tp " + name + " " + (base.getX() + 0.5) + " " + (base.getY() + 1)
                + " " + (base.getZ() + 0.5) + " 0 90");
        c.waitTicks(8);
    }
    private static void gesture(ClientGameTestContext c) {
        c.getInput().holdShift();
        try { c.waitTicks(2); c.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_LEFT, 20); }
        finally { c.getInput().releaseShift(); }
        c.waitTicks(5);
    }
    private static void clearOnce(ClientGameTestContext c, List<String> messages, String scenario) {
        long before = count(c, messages);
        gesture(c);
        check(c.computeOnClient(mc -> ClientState.part() == null && ClientState.partOutline() == null
                && !ClientState.flag("selectingPart") && mc.gui.screen() == null), scenario + ": selection was not cleared");
        check(count(c, messages) - before == 1, scenario + ": one held press did not clear exactly once");
    }
    private static long count(ClientGameTestContext c, List<String> messages) {
        return c.computeOnClient(mc -> messages.stream().filter(line -> line.contains("Box cleared.")).count());
    }
    private static void command(ClientGameTestContext c, String command) {
        c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + command)); c.waitTicks(8);
    }
    private static String pos(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
