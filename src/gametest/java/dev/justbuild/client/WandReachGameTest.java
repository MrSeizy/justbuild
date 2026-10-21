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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.platform.InputConstants;

/** Distant wand clicks and relocation work at a shallow angle without extending normal block interaction. */
public final class WandReachGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext c) {
        List<String> messages = new ArrayList<>(); AtomicBoolean recording = new AtomicBoolean(true);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (recording.get() && !overlay) messages.add(message.getString()); });
        try (TestSingleplayerContext w = c.worldBuilder().create()) {
            w.getConnection().waitForChunksRender();
            String name = c.computeOnClient(mc -> mc.player.getName().getString());
            BlockPos base = c.computeOnClient(mc -> mc.player.blockPosition().above(12));
            BlockPos first = base.offset(24, 0, -4), second = base.offset(40, 0, 5), moved = base.offset(16, 0, -6);
            w.getServer().runOnServer(s -> {
                for (BlockPos p : BlockPos.betweenClosed(base.offset(-2, 0, -12), base.offset(75, 5, 12)))
                    s.overworld().setBlock(p, p.getY() == base.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                s.overworld().setBlock(second, Blocks.BARREL.defaultBlockState(), Block.UPDATE_CLIENTS);
            });
            for (String mode : List.of("creative", "survival")) {
                w.getServer().runCommand("gamemode " + mode + " " + name);
                w.getServer().runCommand("tp " + name + " " + (base.getX() + 0.5) + " " + (base.getY() + 1) + " " + (base.getZ() + 0.5));
                command(c, "zone clear"); command(c, "zone wand");
                aim(c, first); click(c, messages, InputConstants.MOUSE_BUTTON_LEFT, "Zone corner 1:");
                aim(c, second); click(c, messages, InputConstants.MOUSE_BUTTON_RIGHT, "Build zone:");
                check(c.computeOnClient(mc -> ClientState.zone().minX() == first.getX() && ClientState.zone().maxX() == second.getX()
                        && mc.gui.screen() == null), mode + ": far clicks did not select the zone or opened a barrel");
                check(c.computeOnClient(mc -> mc.player.blockInteractionRange() < 7 && Math.abs(mc.player.getXRot()) < 10),
                        mode + ": selection needed a steep angle or changed ordinary interaction range");
                c.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false)); c.takeScreenshot("wand-reach-" + mode);
                // Pick the near corner handle, then place it on a block still well outside normal reach.
                PreviewControlsGameTest.runOnLook(c, c.computeOnClient(mc -> SelectionHandles.zoneCornerPosition(ClientState.zone(), 0)));
                c.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_RIGHT, 20); c.waitFor(mc -> SelectionHandles.moving());
                check(c.computeOnClient(mc -> SelectionHandles.targetHint(mc).equals("New corner: " + first.getX() + ", " + first.getZ())),
                        mode + ": distant handle pickup immediately moved the preview");
                aim(c, moved); c.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_RIGHT, 20);
                c.waitFor(mc -> !SelectionHandles.moving() && ClientState.zone().minX() == moved.getX());
                check(c.computeOnClient(mc -> ClientState.zone().maxX() == second.getX()), "far relocation changed the opposite corner");

                command(c, "box clear"); command(c, "box wand");
                aim(c, first); click(c, messages, InputConstants.MOUSE_BUTTON_LEFT, "Box corner 1:");
                aim(c, second); click(c, messages, InputConstants.MOUSE_BUTTON_RIGHT, "Box:");
                check(c.computeOnClient(mc -> BuildRegion.between(mc.level.dimension(), first, second).equals(ClientState.part())),
                        mode + ": far part corners selected the wrong bounds");
                PreviewControlsGameTest.runOnLook(c, c.computeOnClient(mc -> SelectionHandles.partCornerPosition(ClientState.part(), 0)));
                c.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_RIGHT, 20); c.waitFor(mc -> SelectionHandles.moving());
                aim(c, moved); c.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_RIGHT, 20);
                c.waitFor(mc -> !SelectionHandles.moving() && ClientState.part().min().equals(moved));

                // The 64-block limit and a true air click must not start or extend a selection.
                var part = c.computeOnClient(mc -> ClientState.part());
                PreviewControlsGameTest.runOnLook(c, new Vec3(base.getX() + 72.5, base.getY() + 1, base.getZ() + 0.5));
                check(c.computeOnClient(mc -> ZoneWandInput.target(mc) == null), "target exceeded 64 blocks");
                c.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_RIGHT, 20);
                c.runOnClient(mc -> mc.player.setXRot(-90)); c.waitTicks(3); c.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_LEFT, 20);
                check(c.computeOnClient(mc -> part.equals(ClientState.part())), "out-of-range/air click changed the part");
            }
            command(c, "zone wand"); command(c, "zone adjust polygon start");
            for (BlockPos p : List.of(first, second, base.offset(24, 0, 8))) {
                aim(c, p); c.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_RIGHT, 20); c.waitTicks(3);
            }
            check(c.computeOnClient(mc -> ClientState.polygonDraft().points().size() == 3), "held remote polygon clicks repeated");
            command(c, "zone adjust polygon finish");
            // Sneaking at a far block selects a building, rather than treating it as air and switching wand modes.
            aim(c, second); c.getInput().holdShift(); c.waitTicks(3);
            click(c, messages, InputConstants.MOUSE_BUTTON_RIGHT, "That block is not part of a saved AI build.");
            c.getInput().releaseShift(); c.waitTicks(3);
            check(c.computeOnClient(mc -> !ZoneWand.isPart(mc.player.getMainHandItem())), "far sneak-click switched modes");

            // A wall intercepts the ray: it cannot select a block through the wall.
            BlockPos wall = base.offset(10, 2, 0);
            w.getServer().runOnServer(s -> s.overworld().setBlock(wall, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS));
            PreviewControlsGameTest.runOnLook(c, new Vec3(base.getX() + 30.5, base.getY() + 1, base.getZ() + 0.5));
            c.waitFor(mc -> ZoneWandInput.target(mc) != null && ZoneWandInput.target(mc).getBlockPos().equals(wall));
            c.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_LEFT, 20); c.waitTicks(3);
            command(c, "zone 2 " + pos(wall));
            check(c.computeOnClient(mc -> ClientState.zone().minX() == wall.getX()), "far click selected through a solid wall");

            // Ordinary tools keep vanilla reach when the wand is put away.
            w.getServer().runOnServer(s -> s.getPlayerList().getPlayers().getFirst().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE)));
            c.waitTicks(5); aim(c, second); c.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_LEFT, 20);
            check(w.getServer().computeOnServer(s -> s.overworld().getBlockState(second).is(Blocks.BARREL)
                    && s.overworld().getBlockState(first).is(Blocks.STONE)), "extended wand input broke a block or changed ordinary tool reach");

            // An offhand wand must not steal a main-hand bow's right-click, but works with an empty main hand.
            var selected = c.computeOnClient(mc -> ClientState.zone());
            w.getServer().runCommand("gamemode creative " + name);
            w.getServer().runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
                player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ZoneWand.ITEM));
            });
            c.waitTicks(5); aim(c, second);
            c.getInput().holdMouse(InputConstants.MOUSE_BUTTON_RIGHT);
            try {
                c.waitTicks(8);
                check(c.computeOnClient(mc -> mc.player.isUsingItem() && selected.equals(ClientState.zone())),
                        "offhand wand interrupted the main-hand bow or changed the selection");
            } finally { c.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT); }
            w.getServer().runOnServer(s -> s.getPlayerList().getPlayers().getFirst().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
            command(c, "zone 1 " + pos(first)); aim(c, second);
            click(c, messages, InputConstants.MOUSE_BUTTON_RIGHT, "Build zone:");
            check(c.computeOnClient(mc -> ClientState.zone().minX() == first.getX() && ClientState.zone().maxX() == second.getX()),
                    "offhand wand with an empty main hand could not select a distant block");

            // Holding use from empty air must remain held when a separate attack click starts a far selection.
            command(c, "zone wand"); c.runOnClient(mc -> mc.player.setXRot(-90)); c.waitTicks(3);
            c.getInput().holdMouse(InputConstants.MOUSE_BUTTON_RIGHT);
            try {
                c.waitTicks(3); aim(c, first);
                c.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_LEFT, 3); c.waitTicks(5);
                check(c.computeOnClient(mc -> mc.options.keyUse.isDown() && ClientState.flag("selectingZone")),
                        "an attack click released an unrelated held use key or repeated its action");
            } finally { c.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT); }
        } finally { recording.set(false); }
    }
    private static void aim(ClientGameTestContext c, BlockPos p) {
        PreviewControlsGameTest.runOnLook(c, new Vec3(p.getX() + 0.5, p.getY() + 1, p.getZ() + 0.5));
        c.waitFor(mc -> ZoneWandInput.target(mc) != null && ZoneWandInput.target(mc).getBlockPos().equals(p));
        check(c.computeOnClient(mc -> mc.hitResult.getType() == HitResult.Type.MISS), "test block was within ordinary reach");
    }
    private static void click(ClientGameTestContext c, List<String> messages, int button, String expected) {
        long before = c.computeOnClient(mc -> messages.stream().filter(m -> m.contains(expected)).count());
        c.getInput().holdMouseFor(button, 20); c.waitTicks(5);
        check(c.computeOnClient(mc -> messages.stream().filter(m -> m.contains(expected)).count()) == before + 1,
                "one held click did not send exactly one action: " + expected);
    }
    private static void command(ClientGameTestContext c, String command) { c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + command)); c.waitTicks(8); }
    private static String pos(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
