package dev.justbuild.client;

import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import com.mojang.blaze3d.platform.InputConstants;

/** Near and distant targets use the wand mode color and enclose the block selected by clicking. */
public final class WandTargetGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext c) {
        try (TestSingleplayerContext w = c.worldBuilder().create()) {
            w.getConnection().waitForChunksRender();
            String name = c.computeOnClient(mc -> mc.player.getName().getString());
            BlockPos base = c.computeOnClient(mc -> mc.player.blockPosition().above(12));
            w.getServer().runCommand("gamemode creative " + name);
            w.getServer().runOnServer(s -> {
                for (BlockPos p : BlockPos.betweenClosed(base.offset(-4, 0, -10), base.offset(40, 5, 10)))
                    s.overworld().setBlock(p, (p.getY() != base.getY() ? Blocks.AIR
                            : ((p.getX() + p.getZ()) & 1) == 0 ? Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.WHITE) : Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.GRAY)).defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                for (int distance : new int[] {3, 8, 24})
                    s.overworld().setBlock(base.offset(distance, 0, 0), Blocks.LAPIS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
            });
            w.getServer().runCommand("tp " + name + " " + (base.getX() + 0.5) + " " + (base.getY() + 1)
                    + " " + (base.getZ() + 0.5));
            c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild zone wand"));
            c.waitTicks(12);
            w.getConnection().waitForChunksRender();
            for (boolean part : new boolean[] {false, true}) for (int distance : new int[] {3, 8, 24}) {
                String mode = part ? "box" : "zone";
                command(c, mode + " wand");
                BlockPos target = base.offset(distance, 0, 0);
                PreviewControlsGameTest.runOnLook(c, new Vec3(target.getX() + 0.5, target.getY() + 1, target.getZ() + 0.5));
                c.waitTicks(5);
                c.runOnClient(mc -> {
                    var hit = ZoneWandInput.target(mc);
                    if (hit == null || !hit.getBlockPos().equals(target)) throw new AssertionError("Wrong wand target: " + hit);
                    if ((mc.hitResult.getType() == HitResult.Type.BLOCK) != (distance == 3))
                        throw new AssertionError("Fixture did not exercise the expected close/distant targeting path");
                    mc.gui.hud.getChat().clearMessages(false);
                });
                int[] face = projectedTop(c, target);
                checkHighlight(c.takeScreenshot("wand-target-" + mode + "-" + distance), face, part);
                c.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_LEFT, 3); c.waitTicks(5);
                c.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_RIGHT, 3); c.waitTicks(5);
                if (!c.computeOnClient(mc -> part ? ClientState.part() != null
                        && ClientState.part().min().equals(target) && ClientState.part().max().equals(target)
                        : ClientState.zone() != null && ClientState.zone().minX() == target.getX()
                        && ClientState.zone().maxX() == target.getX() && ClientState.zone().minZ() == target.getZ()
                        && ClientState.zone().maxZ() == target.getZ()))
                    throw new AssertionError("Click did not select the highlighted block in " + mode + " mode");
                command(c, mode + " clear");
            }
        }
    }

    /** Project the actual visible block face, independently of the gizmo geometry/rendering. */
    private static int[] projectedTop(ClientGameTestContext c, BlockPos block) {
        return c.computeOnClient(mc -> {
            var camera = mc.gameRenderer.mainCamera();
            var matrix = camera.getViewRotationProjectionMatrix(new Matrix4f());
            int[] bounds = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (int dx : new int[] {0, 1}) for (int dz : new int[] {0, 1}) {
                var p = new Vec3(block.getX() + dx, block.getY() + 1, block.getZ() + dz).subtract(camera.position());
                var clip = matrix.transform(new Vector4f((float) p.x, (float) p.y, (float) p.z, 1));
                int x = Math.round((clip.x / clip.w + 1) * mc.getWindow().getWidth() / 2);
                int y = Math.round((1 - clip.y / clip.w) * mc.getWindow().getHeight() / 2);
                bounds[0] = Math.min(bounds[0], x); bounds[1] = Math.min(bounds[1], y);
                bounds[2] = Math.max(bounds[2], x); bounds[3] = Math.max(bounds[3], y);
            }
            return bounds;
        });
    }

    private static void checkHighlight(Path screenshot, int[] face, boolean part) {
        try {
            var image = ImageIO.read(screenshot.toFile());
            int visible = 0, displaced = 0;
            // Only the world around the crosshair; exclude the wand model and HUD.
            for (int y = image.getHeight() / 3; y < image.getHeight() * 2 / 3; y++)
                for (int x = image.getWidth() / 3; x < image.getWidth() * 2 / 3; x++) {
                    int rgb = image.getRGB(x, y), r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
                    boolean stroke = part ? r > 205 && r < 225 && g > 130 && g < 150 && b > 245
                            : r > 245 && g > 195 && g < 220 && b > 95 && b < 120;
                    if (!stroke) continue;
                    visible++;
                    if (x < face[0] - 3 || x > face[2] + 3 || y < face[1] - 3 || y > face[3] + 3) displaced++;
                }
            if (visible < 10 || displaced > 2)
                throw new AssertionError("Highlight must follow the visible block face: visible=" + visible + ", displaced=" + displaced);
        } catch (IOException e) { throw new AssertionError("Could not inspect wand highlight", e); }
    }

    private static void command(ClientGameTestContext c, String command) {
        c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + command)); c.waitTicks(5);
    }
}
