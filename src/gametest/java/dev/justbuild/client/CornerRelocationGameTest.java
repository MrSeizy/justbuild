package dev.justbuild.client;

import dev.justbuild.world.ZoneWand;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Actual mouse presses relocate corners without freezing movement or sending ordinary wand actions. */
public final class CornerRelocationGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext c) {
        try (TestSingleplayerContext w = c.worldBuilder().create()) {
            w.getClientLevel().waitForChunksRender();
            String name = c.computeOnClient(mc -> mc.player.getName().getString());
            BlockPos base = c.computeOnClient(mc -> mc.player.blockPosition().above(12));
            w.getServer().runCommand("gamemode creative " + name);
            w.getServer().runOnServer(s -> {
                for (BlockPos p : BlockPos.betweenClosed(base.offset(-12, 0, -12), base.offset(12, 6, 12)))
                    s.overworld().setBlock(p, p.getY() == base.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                s.overworld().setBlock(base.offset(-6, 0, -6), Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
                s.overworld().setBlock(base.offset(6, 3, 6), Blocks.AMETHYST_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                s.overworld().setBlock(base.offset(-4, 4, 5), Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                s.overworld().setBlock(base.offset(5, 1, -3), Blocks.EMERALD_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
            });
            command(c, "zone 1 " + pos(base.offset(-4, 0, -4)));
            command(c, "zone 2 " + pos(base.offset(4, 0, 4)));
            command(c, "zone adjust height " + base.getY() + " " + (base.getY() + 5));
            command(c, "zone wand");
            var original = c.computeOnClient(mc -> ClientState.zone());
            pick(c, w, name, base, false, 0);
            c.runOnClient(mc -> mc.gui.getChat().clearMessages(false));
            c.takeScreenshot("zone-corner-pickup-stays-put");
            Vec3 before = c.computeOnClient(mc -> mc.player.position());
            c.getInput().holdKeyFor(GLFW.GLFW_KEY_W, 10);
            check(c.computeOnClient(mc -> SelectionHandles.moving() && mc.screen == null && mc.player.position().distanceTo(before) > 0.5),
                    "walking did not remain available while holding a corner");
            lookDown(c, w, name, base.offset(-6, 0, -6));
            check(c.computeOnClient(mc -> SelectionHandles.targetHint(mc).equals("New corner: "
                    + (base.getX() - 6) + ", " + (base.getZ() - 6))), "preview did not follow the new aim");
            check(c.computeOnClient(mc -> original.equals(ClientState.zone())
                    && SelectionHandles.displayedZone(mc, ClientState.zone()).equals(original.corner(0, base.getX() - 6, base.getZ() - 6))),
                    "the zone outline did not move independently of the committed selection");
            c.runOnClient(mc -> mc.gui.getChat().clearMessages(false));
            c.takeScreenshot("corner-relocation-preview");
            right(c);
            c.waitFor(mc -> ClientState.zone().minX() == base.getX() - 6);
            check(c.computeOnClient(mc -> !SelectionHandles.moving() && mc.screen == null
                    && ClientState.zone().equals(original.corner(0, base.getX() - 6, base.getZ() - 6))),
                    "relocation changed another corner/height, repeated, or opened the chest");

            // Invalid placement and air clicks keep the same corner picked up.
            var moved = c.computeOnClient(mc -> ClientState.zone());
            pick(c, w, name, base, false, 0);
            lookDown(c, w, name, base.offset(-7, 0, -7));
            var lastValidZone = c.computeOnClient(mc -> SelectionHandles.displayedZone(mc, ClientState.zone()));
            lookDown(c, w, name, base.offset(9, 0, 9)); right(c);
            check(c.computeOnClient(mc -> SelectionHandles.moving() && moved.equals(ClientState.zone())
                    && lastValidZone.equals(SelectionHandles.displayedZone(mc, ClientState.zone()))),
                    "invalid corner changed the selection or lost its last valid outline");
            c.runOnClient(mc -> mc.player.setXRot(-90)); c.waitTicks(3); right(c);
            check(c.computeOnClient(mc -> SelectionHandles.moving() && moved.equals(ClientState.zone())), "air click placed a corner");
            lookDown(c, w, name, base.offset(-7, 0, -7));
            c.getInput().holdMouseFor(GLFW.GLFW_MOUSE_BUTTON_LEFT, 20); c.waitTicks(3);
            check(c.computeOnClient(mc -> !SelectionHandles.moving() && moved.equals(ClientState.zone())
                    && moved.equals(SelectionHandles.displayedZone(mc, ClientState.zone()))), "cancel did not restore the original zone outline");

            // Cancel on Esc, mode/selection changes, or putting the wand away.
            pick(c, w, name, base, false, 0);
            c.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); c.waitTicks(3);
            check(c.computeOnClient(mc -> !SelectionHandles.moving()), "Esc kept a corner picked up");
            c.runOnClient(mc -> mc.setScreen(null));
            pick(c, w, name, base, false, 0);
            command(c, "box wand");
            check(c.computeOnClient(mc -> !SelectionHandles.moving()), "mode change kept a corner picked up");
            command(c, "zone wand"); pick(c, w, name, base, false, 0);
            command(c, "zone adjust move 1 0 0");
            check(c.computeOnClient(mc -> !SelectionHandles.moving()), "selection change kept a stale corner picked up");
            pick(c, w, name, base, false, 0);
            c.getInput().pressKey(GLFW.GLFW_KEY_2); c.waitTicks(3);
            check(c.computeOnClient(mc -> !SelectionHandles.moving()), "putting away the wand kept a corner picked up");

            // All eight part corners can be picked independently, including mixed minimum/maximum axes.
            command(c, "box 1 " + pos(base.offset(-4, 0, -4)));
            command(c, "box 2 " + pos(base.offset(4, 5, 4)));
            command(c, "box wand");
            var originalPart = c.computeOnClient(mc -> ClientState.part());
            for (int i = 0; i < 8; i++) {
                pick(c, w, name, base, true, i);
                c.getInput().holdMouseFor(GLFW.GLFW_MOUSE_BUTTON_LEFT, 3); c.waitTicks(3);
                check(c.computeOnClient(mc -> !SelectionHandles.moving() && originalPart.equals(ClientState.part())),
                        "cancelling a part corner move changed the selection");
            }
            pick(c, w, name, base, true, 0);
            var beforePartMove = c.computeOnClient(mc -> ClientState.part());
            lookDown(c, w, name, base.offset(-2, 0, -2));
            check(c.computeOnClient(mc -> beforePartMove.equals(ClientState.part())
                    && SelectionHandles.displayedPart(mc, ClientState.partOutline()).min().equals(base.offset(-2, 0, -2))
                    && SelectionHandles.displayedPart(mc, ClientState.partOutline()).max().equals(beforePartMove.max())),
                    "the purple outline did not follow the part corner before placement");
            c.runOnClient(mc -> mc.gui.getChat().clearMessages(false));
            c.takeScreenshot("part-single-outline-preview");
            c.getInput().holdMouseFor(GLFW.GLFW_MOUSE_BUTTON_LEFT, 3); c.waitTicks(3);
            check(c.computeOnClient(mc -> !SelectionHandles.moving() && beforePartMove.equals(ClientState.part())
                    && beforePartMove.equals(SelectionHandles.displayedPart(mc, ClientState.partOutline()))),
                    "cancel did not restore the original part outline");
            pick(c, w, name, base, true, 0);
            lookDown(c, w, name, base.offset(-2, 0, -2)); right(c);
            c.waitFor(mc -> ClientState.part().min().equals(base.offset(-2, 0, -2)));
            pick(c, w, name, base, true, 7);
            lookDown(c, w, name, base.offset(6, 3, 6)); right(c);
            c.waitFor(mc -> ClientState.part().max().equals(base.offset(6, 3, 6)));
            check(c.computeOnClient(mc -> ClientState.part().min().equals(base.offset(-2, 0, -2))), "moving part maximum changed minimum");

            pick(c, w, name, base, true, 6);
            lookDown(c, w, name, base.offset(-4, 4, 5)); right(c);
            c.waitFor(mc -> ClientState.part().min().equals(base.offset(-4, 0, -2))
                    && ClientState.part().max().equals(base.offset(6, 4, 5)));
            pick(c, w, name, base, true, 1);
            lookDown(c, w, name, base.offset(5, 1, -3));
            c.runOnClient(mc -> mc.gui.getChat().clearMessages(false));
            c.takeScreenshot("part-corner-relocation-preview");
            right(c);
            c.waitFor(mc -> ClientState.part().min().equals(base.offset(-4, 1, -3))
                    && ClientState.part().max().equals(base.offset(5, 4, 5)));
            var resizedPart = c.computeOnClient(mc -> ClientState.part());
            pick(c, w, name, base, true, 6);
            lookDown(c, w, name, base.offset(9, 0, 9)); right(c);
            check(c.computeOnClient(mc -> SelectionHandles.moving() && resizedPart.equals(ClientState.part())),
                    "an invalid mixed part corner changed the selection");
            c.getInput().holdMouseFor(GLFW.GLFW_MOUSE_BUTTON_LEFT, 3); c.waitTicks(3);
            command(c, "box adjust corner 6 " + pos(base.offset(9, 0, 9)));
            command(c, "box adjust corner 8 " + pos(base));
            check(c.computeOnClient(mc -> resizedPart.equals(ClientState.part())), "invalid corner command changed the part");

            // Flat selections still expose distinct upper and lower handles.
            command(c, "box 1 " + pos(base.offset(-4, 0, -4)));
            command(c, "box 2 " + pos(base.offset(4, 0, 4)));
            var flat = c.computeOnClient(mc -> ClientState.part());
            check(java.util.stream.IntStream.range(0, 8).mapToObj(i -> SelectionHandles.partCornerPosition(flat, i))
                    .distinct().count() == 8, "flat selection handles overlap");
            pick(c, w, name, base, true, 6);
            lookDown(c, w, name, base.offset(-4, 4, 5)); right(c);
            c.waitFor(mc -> ClientState.part().min().equals(flat.min()) && ClientState.part().max().equals(base.offset(4, 4, 5)));
            w.getServer().runCommand("tp " + name + " " + (base.getX() + 10.5) + " " + (base.getY() + 9)
                    + " " + (base.getZ() + 12.5) + " 140 28");
            c.waitTicks(8);
            c.runOnClient(mc -> mc.gui.getChat().clearMessages(false));
            c.takeScreenshot("part-eight-corner-handles");

            command(c, "zone wand"); command(c, "zone adjust polygon start");
            command(c, "zone 1 " + pos(base.offset(-4, 0, -4)));
            command(c, "zone 2 " + pos(base.offset(4, 0, -4)));
            command(c, "zone 2 " + pos(base.offset(4, 0, 4)));
            command(c, "zone 2 " + pos(base.offset(-4, 0, 4)));
            command(c, "zone adjust polygon finish");
            var polygon = c.computeOnClient(mc -> ClientState.zone());
            pick(c, w, name, base, false, 1);
            lookDown(c, w, name, base.offset(6, 0, -5));
            check(c.computeOnClient(mc -> polygon.equals(ClientState.zone())
                    && polygon.corner(1, base.getX() + 6, base.getZ() - 5).equals(SelectionHandles.displayedZone(mc, ClientState.zone()))),
                    "polygon outline did not follow its moving vertex");
            c.runOnClient(mc -> mc.gui.getChat().clearMessages(false));
            c.takeScreenshot("polygon-single-outline-preview");
            right(c);
            c.waitFor(mc -> polygon.corner(1, base.getX() + 6, base.getZ() - 5).equals(ClientState.zone()));
            check(w.getServer().computeOnServer(s -> s.overworld().getBlockState(base.offset(-6, 0, -6)).is(Blocks.CHEST)
                    && s.overworld().getBlockState(base.offset(-7, 0, -7)).is(Blocks.STONE)), "wand relocation or cancel changed a block");
            // Disconnect also clears a pending move.
            pick(c, w, name, base, false, 0);
        }
        c.waitFor(mc -> !SelectionHandles.moving());
    }
    private static void pick(ClientGameTestContext c, TestSingleplayerContext w, String name, BlockPos base, boolean part, int corner) {
        w.getServer().runCommand("tp " + name + " " + (base.getX() + 0.5) + " " + (base.getY() + 1) + " " + (base.getZ() + 8.5) + " 180 20");
        c.waitTicks(8);
        Vec3 point = c.computeOnClient(mc -> {
            check(ZoneWand.isPart(mc.player.getMainHandItem()) == part, "wrong wand mode");
            if (part) return SelectionHandles.partCornerPosition(ClientState.part(), corner);
            return SelectionHandles.zoneCornerPosition(ClientState.zone(), corner);
        });
        PreviewControlsGameTest.runOnLook(c, point); right(c);
        c.waitFor(mc -> SelectionHandles.moving());
        check(c.computeOnClient(mc -> SelectionHandles.title().equals("Moving " + (part ? "box" : "zone") + " corner " + (corner + 1))),
                "picked a different corner than the one aimed at");
        check(c.computeOnClient(mc -> mc.screen == null), "picking up a corner opened a screen");
        check(c.computeOnClient(mc -> part
                ? ClientState.part().equals(SelectionHandles.displayedPart(mc, ClientState.partOutline()))
                : ClientState.zone().equals(SelectionHandles.displayedZone(mc, ClientState.zone()))),
                "picking up a handle moved its outline before changing aim");
        if (!part) check(c.computeOnClient(mc -> {
            var p = SelectionHandles.corners(ClientState.zone()).get(corner);
            return SelectionHandles.targetHint(mc).equals("New corner: " + p.x() + ", " + p.z());
        }), "picking up the raised zone handle moved the preview before changing aim");
    }
    private static void lookDown(ClientGameTestContext c, TestSingleplayerContext w, String name, BlockPos p) {
        w.getServer().runCommand("tp " + name + " " + (p.getX() + 0.5) + " " + (p.getY() + 1) + " " + (p.getZ() + 0.5) + " 0 90");
        c.waitTicks(8);
        c.waitFor(mc -> mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(p));
    }
    private static void right(ClientGameTestContext c) { c.getInput().holdMouseFor(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 20); c.waitTicks(3); }
    private static void command(ClientGameTestContext c, String command) { c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + command)); c.waitTicks(8); }
    private static String pos(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
