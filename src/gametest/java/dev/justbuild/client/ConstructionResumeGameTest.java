package dev.justbuild.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.justbuild.JustBuildMod;
import dev.justbuild.construction.*;
import dev.justbuild.plan.*;
import dev.justbuild.prompt.BuildConstraints;
import dev.justbuild.session.PreviewStore;
import dev.justbuild.world.*;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Released preview -> construction handoff, real world restarts, and live protections after recovery. */
public final class ConstructionResumeGameTest implements FabricClientGameTest {
    private static final BlockPos AT = new BlockPos(0, 100, 0), CHEST = AT.offset(0, 0, 3);
    @Override public void runTest(ClientGameTestContext c) {
        try {
            TestWorldSave save; String actor, player;
            try (var world = c.worldBuilder().create()) {
                save = world.getWorldSave(); world.getConnection().waitForChunksRender();
                actor = c.computeOnClient(mc -> mc.player.getUUID().toString()); player = c.computeOnClient(mc -> mc.player.getName().getString());
                world.getServer().runCommand("gamemode creative " + player);
                world.getServer().runCommand("tp " + player + " 0.5 100 1.5 180 20");
                world.getServer().runOnServer(server -> {
                    for (BlockPos p : BlockPos.betweenClosed(AT.offset(-8, -1, -8), AT.offset(8, 5, 8)))
                        server.overworld().setBlock(p, p.getY() == 99 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    server.overworld().setBlock(CHEST, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    ((ChestBlockEntity) server.overworld().getBlockEntity(CHEST)).setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
                });
                world.getServer().runCommand("gamemode survival " + player);
            }
            var previewStore = new PreviewStore(save.getSaveDirectory().resolve("justbuild/previews"));
            BuildPlan plan = new BuildPlan("Recovered survival porch", "Three planks", List.of(Operation.fill("oak_planks", new Cell(0, 0, 0), new Cell(2, 0, 0), false)));
            var choice = new PreviewStore.Choice(plan, "Build a porch", null, Level.OVERWORLD, AT, Direction.NORTH, null, null,
                    BuildZone.between(Level.OVERWORLD, AT.offset(-3, -1, -3), AT.offset(3, 4, 4)), null, List.of(), BuildConstraints.NONE, null, null);
            previewStore.save(actor, new PreviewStore.Saved(List.of(choice), 0));
            UUID projectId;
            try (var world = save.open()) {
                world.getConnection().waitForChunksRender();
                c.waitFor(mc -> ClientState.string("retainedPlan", null) != null, 200);
                command(c, "retry"); c.waitFor(mc -> ClientState.preview() != null, 200);
                c.runOnClient(mc -> mc.gui.setScreen(new JustBuildScreen()));
                c.waitTicks(3); c.takeScreenshot("construction-preview-entry");
                c.runOnClient(mc -> ConstructionMaterialsGameTest.checkLayout(mc.gui.screen()));
                c.clickScreenButton("Guided Build"); c.waitForScreen(ConstructionSetupScreen.class);
                c.runOnClient(mc -> ConstructionMaterialsGameTest.checkLayout(mc.gui.screen()));
                c.takeScreenshot("construction-preview-chooser");
                c.clickScreenButton("Guide"); c.waitForScreen(ConstructionScreen.class);
                c.waitFor(mc -> ClientState.preview() == null && ConstructionClient.active(), 200);
                check(previewStore.load(actor) == null, "Pinning left a replayable saved preview");
                projectId = c.computeOnClient(mc -> ConstructionClient.state().id());
                world.getServer().runOnServer(server -> server.overworld().setBlock(AT, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS));
                c.waitFor(mc -> ConstructionClient.state().done() == 1, 100);
                c.clickScreenButton("Pause"); c.waitTicks(6); c.clickScreenButton("Cobble"); c.waitTicks(6); c.clickScreenButton("Ghosts: layer");
                c.waitTicks(6); c.clickScreenButton("Keep building");
                c.runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(CHEST))); c.waitTicks(5);
                c.getInput().pressKey(InputConstants.KEY_RSHIFT); c.waitForScreen(ConstructionScreen.class);
                c.clickScreenButton("Link chest"); c.waitFor(mc -> ConstructionClient.state().chest() != null, 100);
                c.clickScreenButton("Keep building");
            }
            var constructionStore = new ConstructionStore(save.getSaveDirectory().resolve("justbuild/construction"));
            var stored = constructionStore.load(UUID.fromString(actor));
            check(stored != null && stored.id.equals(projectId) && stored.paused && !stored.currentLayer
                    && stored.mode == ConstructionProject.Mode.COBBLE && CHEST.equals(stored.chest), "Restart lost construction settings");
            // New locks on disk must apply even though the blueprint was accepted earlier.
            new RegionStore(save.getSaveDirectory().resolve("justbuild/locked-areas.json")).save(List.of(new RegionStore.Locked(
                    "aabbccdd", actor, "Protected after restart", BuildRegion.between(Level.OVERWORLD, AT.east(), AT.east()))));
            try (var world = save.open()) {
                world.getConnection().waitForChunksRender();
                c.waitFor(mc -> ConstructionClient.active() && ConstructionClient.state().done() == 1, 200);
                check(c.computeOnClient(mc -> ConstructionClient.state().paused()), "Restart resumed automatic construction without a player action");
                c.waitTicks(30);
                check(world.getServer().computeOnServer(server -> server.overworld().getBlockState(AT.east()).isAir()), "Paused recovery changed the world");
                command(c, "construction resume");
                // Cobble builds past the protected cell and reports it.
                c.waitFor(mc -> ConstructionClient.state().done() == 2 && ConstructionClient.state().status().startsWith("This position is protected"), 200);
                check(world.getServer().computeOnServer(server -> ((ChestBlockEntity) server.overworld().getBlockEntity(CHEST)).getItem(0).getCount() == 1
                        && server.overworld().getBlockState(AT.east()).isAir()), "Protected placement changed the world or spent supplies");
                command(c, "unlock aabbccdd");
                c.waitFor(mc -> ConstructionClient.state().done() == 3, 300);
                check(world.getServer().computeOnServer(server -> ((ChestBlockEntity) server.overworld().getBlockEntity(CHEST)).isEmpty()
                        && JustBuildMod.manager().savedBuildCount() == 0), "Construction leaked free undo history or miscounted resumed purchases");
                c.getInput().pressKey(InputConstants.KEY_RSHIFT); c.waitForScreen(ConstructionScreen.class);
                c.takeScreenshot("construction-recovered-complete");
                c.clickScreenButton("End project"); c.clickScreenButton("End project?");
                c.waitFor(mc -> !ConstructionClient.active(), 100);
                check(constructionStore.load(UUID.fromString(actor)) == null, "Closing left a stale construction file");
            }
        } catch (Exception e) { throw new AssertionError("Construction recovery failed", e); }
    }
    private static void command(ClientGameTestContext c, String command) {
        c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + command)); c.waitTicks(10);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
