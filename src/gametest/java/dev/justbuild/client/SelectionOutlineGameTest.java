package dev.justbuild.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Checks real shader output for a solid stroke and blended edge pixels, plus held-wand visibility. */
public final class SelectionOutlineGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext c) {
        try (TestSingleplayerContext w = c.worldBuilder().create()) {
            w.getClientLevel().waitForChunksRender();
            String name = c.computeOnClient(mc -> mc.player.getName().getString());
            BlockPos base = c.computeOnClient(mc -> mc.player.blockPosition().above(12));
            w.getServer().runOnServer(s -> {
                for (BlockPos p : BlockPos.betweenClosed(base.offset(-28, -1, -28), base.offset(28, -1, 28)))
                    s.overworld().setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            });
            command(c, "zone 1 " + pos(base.offset(-9, -1, -8)));
            command(c, "zone 2 " + pos(base.offset(9, -1, 8)));
            command(c, "box 1 " + pos(base.offset(-6, 0, -5)));
            command(c, "box 2 " + pos(base.offset(6, 7, 5)));
            command(c, "box wand");
            // Spectator keeps the camera still while the two images are captured.
            w.getServer().runCommand("gamemode spectator " + name);
            w.getServer().runCommand("tp " + name + " " + (base.getX() + 17.5) + " " + (base.getY() + 14)
                    + " " + (base.getZ() + 20.5) + " 140 25");
            c.runOnClient(mc -> mc.options.hideGui = true);
            c.waitTicks(12);
            Path visible = c.takeScreenshot("selection-outlines-smooth");
            w.getServer().runOnServer(s -> s.getPlayerList().getPlayers().getFirst()
                    .setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
            c.waitTicks(8);
            Path hidden = c.takeScreenshot("selection-outlines-hidden");
            checkPixels(visible, hidden);

            command(c, "box wand");
            w.getServer().runCommand("tp " + name + " " + (base.getX() + 0.5) + " " + (base.getY() + 2)
                    + " " + (base.getZ() + 0.5) + " 45 0");
            c.waitTicks(8);
            c.takeScreenshot("selection-outlines-inside");
            command(c, "box clear");
            command(c, "zone wand");
            command(c, "zone adjust polygon start");
            command(c, "zone 1 " + pos(base.offset(-8, -1, -6)));
            command(c, "zone 2 " + pos(base.offset(7, -1, -3)));
            command(c, "zone 2 " + pos(base.offset(3, -1, 8)));
            command(c, "zone adjust polygon finish");
            w.getServer().runCommand("tp " + name + " " + (base.getX() + 17.5) + " " + (base.getY() + 7)
                    + " " + (base.getZ() + 20.5) + " 140 15");
            c.waitTicks(8);
            c.takeScreenshot("selection-outlines-polygon");
        } finally {
            c.runOnClient(mc -> mc.options.hideGui = false);
        }
    }

    private static void checkPixels(Path visible, Path hidden) {
        try {
            BufferedImage shown = ImageIO.read(visible.toFile()), background = ImageIO.read(hidden.toFile());
            int solid = 0, feathered = 0, hiddenPurple = 0;
            for (int y = shown.getHeight() / 2; y < shown.getHeight(); y++) for (int x = 0; x < shown.getWidth(); x++) {
                int color = shown.getRGB(x, y), under = background.getRGB(x, y);
                int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
                int ur = under >> 16 & 255, ug = under >> 8 & 255, ub = under & 255;
                if (ur - ug > 20 && ub - ur > 10) hiddenPurple++;
                if (r - g < 12 || b - r < 6 || ub > 230) continue;
                double alpha = (double) (b - ub) / (255 - ub);
                if (Math.abs(r - (ur + alpha * (216 - ur))) > 4 || Math.abs(g - (ug + alpha * (139 - ug))) > 4) continue;
                if (alpha > 0.98) solid++;
                if (alpha > 0.1 && alpha < 0.9) feathered++;
            }
            if (solid < 50 || feathered < 100 || hiddenPurple > 5)
                throw new AssertionError("Expected visible antialiased purple edges that hide with the wand: solid="
                        + solid + ", feathered=" + feathered + ", hidden=" + hiddenPurple);
        } catch (IOException e) { throw new AssertionError("Could not inspect selection screenshots", e); }
    }

    private static void command(ClientGameTestContext c, String command) {
        c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + command)); c.waitTicks(8);
    }
    private static String pos(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
}
