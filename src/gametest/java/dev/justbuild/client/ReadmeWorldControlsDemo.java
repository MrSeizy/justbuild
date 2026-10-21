package dev.justbuild.client;

import static dev.justbuild.client.ReadmeDemo.*;

import dev.justbuild.JustBuildMod;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Close-up documentation of actual right-clicks on the controls beside a waiting preview. */
public final class ReadmeWorldControlsDemo implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext c) {
        System.setProperty("java.awt.headless", "true");
        ReadmeDemo film = new ReadmeDemo(captureDirectory());
        int originalFov = c.computeOnClient(mc -> mc.options.fov().get());
        int originalScale = c.computeOnClient(mc -> mc.options.guiScale().get());
        boolean originalHud = c.computeOnClient(mc -> mc.gui.hud.isHidden());
        try (DemoFixture ai = new DemoFixture(); TestSingleplayerContext world = c.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            BlockPos base = c.computeOnClient(mc -> mc.player.blockPosition());
            String player = c.computeOnClient(mc -> mc.player.getName().getString());
            prepare(c, world, player, base); zone(c, base); emptyHand(c, world);
            List<BlockState> unchanged = blocks(world, base);
            ai.answer(cottage(false)); command(c, "build a stone cottage with a spruce roof");
            c.waitFor(mc -> ClientState.preview() != null, 1200);
            BlockPos origin = origin(c);
            closeUp(c, world, player, base);
            c.runOnClient(mc -> mc.gui.toastManager().clear());

            film.begin("24-in-world-controls", "MOVE, CANCEL, AND BUILD IN THE WORLD");
            PreviewControlsGameTest.aim(c, PreviewControls.Control.BUILD);
            film.hold(c, "Controls sit beside the preview. Aim until a label turns yellow.", null, 24);
            film.snapshot(c, "in-world-preview-controls");

            move(c, film, PreviewControls.Control.EAST, origin.east(),
                    "Right-click the right arrow to move the preview one block.");
            move(c, film, PreviewControls.Control.NORTH, origin.east().north(),
                    "The upper arrow moves it away from you.");
            move(c, film, PreviewControls.Control.WEST, origin.north(),
                    "The left arrow moves it back to the left.");
            move(c, film, PreviewControls.Control.SOUTH, origin,
                    "The lower arrow moves it toward you.");
            move(c, film, PreviewControls.Control.UP, origin.above(),
                    "Up raises the preview by one block.");
            move(c, film, PreviewControls.Control.DOWN, origin,
                    "Down lowers it again. Sneaking makes movement steps five blocks.");

            PreviewControlsGameTest.aim(c, PreviewControls.Control.DISCARD);
            film.hold(c, "To cancel this preview, aim at Discard and right-click.", null, 26);
            click(c, PreviewControls.Control.DISCARD);
            c.waitFor(mc -> ClientState.preview() == null, 200);
            if (!unchanged.equals(blocks(world, base))
                    || world.getServer().computeOnServer(s -> JustBuildMod.manager().savedBuildCount()) != 0)
                throw new AssertionError("Moving or discarding the preview changed the world");
            c.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
            film.hold(c, "The preview disappears. No blocks have been placed.", null, 24);

            command(c, "retry"); c.waitFor(mc -> ClientState.preview() != null, 200);
            c.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
            PreviewControlsGameTest.aim(c, PreviewControls.Control.BUILD);
            film.hold(c, "Use /justbuild retry to bring the discarded preview back.", null, 24);
            film.hold(c, "To place it, aim at Build it and right-click.", null, 26);
            click(c, PreviewControls.Control.BUILD);
            c.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
            film.hold(c, "Build it places the approved preview into the world.", null, 32);
            c.waitFor(mc -> ClientState.preview() == null && ClientState.placing() == null
                    && ClientState.string("selectedBuild", null) != null, 1200);
            if (world.getServer().computeOnServer(s -> JustBuildMod.manager().savedBuildCount()) != 1
                    || !world.getServer().computeOnServer(s -> s.overworld().getBlockState(origin.below()).is(Blocks.COBBLESTONE)))
                throw new AssertionError("The in-world Build it control did not place the cottage");
            if (ai.calls.get() != 1) throw new AssertionError("Preview controls or retry called the AI");
            c.runOnClient(mc -> mc.options.fov().set(originalFov));
            camera(c, world, player, base, 10, 8, 16);
            film.hold(c, "The cottage is built. These controls needed no extra AI call.", null, 26);
            film.finish(); ai.verify(); film.writeManifest("clips-controls.json");
        } finally {
            c.runOnClient(mc -> {
                mc.options.fov().set(originalFov); TestHud.hidden(mc, originalHud);
                mc.options.guiScale().set(originalScale); mc.resizeGui();
            });
        }
    }

    private static void closeUp(ClientGameTestContext c, TestSingleplayerContext world, String player, BlockPos at) {
        world.getServer().runCommand("gamemode creative " + player);
        // Stay above the cosmetic worker's route, with the crosshair visible and HUD below the controls.
        world.getServer().runOnServer(s -> {
            var actor = s.getPlayerList().getPlayers().getFirst();
            actor.getAbilities().flying = true; actor.onUpdateAbilities();
        });
        world.getServer().runCommand("tp " + player + " " + (at.getX() + .5) + " " + (at.getY() + 3) + " " + (at.getZ() + 18.5) + " 180 0");
        c.waitTicks(8);
        c.runOnClient(mc -> {
            mc.gui.setScreen(null); dev.justbuild.client.TestHud.hidden(mc, false); mc.options.fov().set(45);
            mc.options.guiScale().set(1); mc.resizeGui(); mc.gui.hud.getChat().clearMessages(false);
        });
        c.waitTicks(5);
    }

    private static void move(ClientGameTestContext c, ReadmeDemo film, PreviewControls.Control control,
            BlockPos expected, String caption) {
        PreviewControlsGameTest.aim(c, control);
        film.hold(c, caption, null, 18);
        String before = c.computeOnClient(mc -> ClientState.preview().position());
        click(c, control);
        c.waitFor(mc -> !ClientState.preview().position().equals(before), 200);
        c.waitTicks(5);
        if (!origin(c).equals(expected)) throw new AssertionError(control + " moved to " + origin(c) + ", expected " + expected);
        c.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
        film.hold(c, "The preview moves; the world stays unchanged until Build it.", null, 12);
    }

    private static void click(ClientGameTestContext c, PreviewControls.Control control) {
        if (c.computeOnClient(mc -> PreviewControls.hovered()) != control)
            throw new AssertionError(control + " is not under the crosshair");
        PreviewControlsGameTest.click(c);
    }

    private static BlockPos origin(ClientGameTestContext c) {
        String[] xyz = c.computeOnClient(mc -> ClientState.preview().position()).split(" · ")[0].split(", ");
        return new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
    }

    private static List<BlockState> blocks(TestSingleplayerContext world, BlockPos base) {
        return world.getServer().computeOnServer(s -> {
            List<BlockState> states = new ArrayList<>();
            for (BlockPos p : BlockPos.betweenClosed(base.offset(-15, -1, -15), base.offset(15, 14, 15)))
                states.add(s.overworld().getBlockState(p));
            return states;
        });
    }
}
