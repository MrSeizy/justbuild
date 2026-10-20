package dev.justbuild.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Guide navigation never plans a build; selection feedback follows real server selection and clearing. */
public final class GettingStartedGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext c) {
        try (var w = c.worldBuilder().create()) {
            w.getClientLevel().waitForChunksRender();
            c.waitFor(mc -> ClientState.known());
            c.runOnClient(mc -> { var panel = new JustBuildScreen(); mc.setScreen(panel); panel.showCreate(); });
            c.clickScreenButton("Start your first build →");
            c.waitForScreen(GettingStartedScreen.class);
            c.runOnClient(mc -> {
                mc.screen.resize(320, 240);
                for (var child : mc.screen.children()) if (child instanceof AbstractWidget widget)
                    check(widget.getX() >= 0 && widget.getRight() <= 320 && widget.getY() >= 0 && widget.getBottom() <= 240,
                            "Guide widget outside narrow screen");
            });
            c.takeScreenshot("first-build-guide-ai");
            c.clickScreenButton("Open AI settings");
            c.waitForScreen(JustBuildScreen.class);
            c.clickScreenButton("Guide"); c.clickScreenButton("2 · Area");
            c.takeScreenshot("first-build-guide-area");
            c.clickScreenButton("Equip Zone wand");
            c.waitFor(mc -> mc.screen == null);
            c.runOnClient(mc -> mc.setScreen(new JustBuildScreen())); c.clickScreenButton("Guide");
            c.clickScreenButton("3 · Request"); c.takeScreenshot("first-build-guide-request");
            c.clickScreenButton("Write a request");
            check(c.computeOnClient(mc -> ClientState.planning() == null && ClientState.preview() == null), "Guide submitted a request");
            command(c, "autobuild on");
            c.clickScreenButton("Guide"); c.clickScreenButton("4 · Preview");
            check(c.computeOnClient(mc -> ((GettingStartedScreen) mc.screen).guidance().contains("Auto-build is ON")),
                    "Guide promised a preview while auto-build was enabled");
            c.takeScreenshot("first-build-guide-autobuild");
            c.runOnClient(mc -> mc.setScreen(null)); command(c, "autobuild off");

            BlockPos at = c.computeOnClient(mc -> mc.player.blockPosition().above(12));
            w.getServer().runOnServer(s -> {
                for (var p : BlockPos.betweenClosed(at.offset(-6, -1, -6), at.offset(8, 4, 8)))
                    s.overworld().setBlock(p, p.getY() == at.getY() - 1 ? Blocks.STONE.defaultBlockState()
                            : p.getX() >= at.getX() && p.getX() <= at.getX() + 4 && p.getZ() >= at.getZ()
                                && p.getZ() <= at.getZ() + 4 && p.getY() <= at.getY() + 2
                                ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            });
            command(c, "box 1 " + pos(at)); command(c, "box 2 " + pos(at.offset(4, 2, 4)));
            command(c, "add Guide sample");
            c.waitFor(mc -> ClientState.selectedBounds() != null);
            check(c.computeOnClient(mc -> ClientState.selectedBounds().min().equals(at)
                    && ClientState.selectedBounds().max().equals(at.offset(4, 2, 4))), "Selected outline has wrong bounds");
            command(c, "box 1 " + pos(at)); command(c, "box 2 " + pos(at.above(2))); command(c, "lock Keep corner");
            command(c, "box 1 " + pos(at.east(2))); command(c, "box 2 " + pos(at.offset(4, 2, 4)));
            command(c, "zone 1 " + pos(at.offset(-2, -1, -2))); command(c, "zone 2 " + pos(at.offset(6, -1, 6)));
            command(c, "zone wand");
            String player = c.computeOnClient(mc -> mc.player.getName().getString());
            w.getServer().runCommand("gamemode spectator " + player);
            w.getServer().runCommand("time set day");
            w.getServer().runCommand("tp " + player + " " + (at.getX() - 9) + " " + (at.getY() + 9) + " " + (at.getZ() - 10) + " -42 25");
            c.waitTicks(12); c.runOnClient(mc -> mc.gui.getChat().clearMessages(true));
            c.takeScreenshot("selected-build-part-protected");
            command(c, "select clear");
            c.waitFor(mc -> ClientState.selectedBounds() == null);
            c.runOnClient(mc -> {
                ClientState.update("{\"selectedBuild\":\"12345678\",\"selectedBounds\":{\"dimension\":\"bad id\"}}");
                check(ClientState.selectedBounds() == null, "Malformed bounds installed");
            });
        }
    }
    private static void command(ClientGameTestContext c, String command) {
        c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + command)); c.waitTicks(8);
    }
    private static String pos(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
