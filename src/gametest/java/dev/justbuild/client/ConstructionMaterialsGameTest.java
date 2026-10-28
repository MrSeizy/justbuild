package dev.justbuild.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.justbuild.JustBuildMod;
import dev.justbuild.construction.*;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Mixed palettes exercise layer totals, world-driven updates, and both paginated native material views. */
public final class ConstructionMaterialsGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext c) {
        Map<BlockPos, BlockState> blueprint = new LinkedHashMap<>();
        List<BlockState> base = List.of(Blocks.STONE.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState(),
                Blocks.GLASS.defaultBlockState(), Blocks.BRICKS.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE));
        int[] counts = {4, 3, 2, 1, 2, 1};
        for (int i = 0; i < base.size(); i++) for (int j = 0; j < counts[i]; j++) blueprint.put(new BlockPos(i * 2, 100, j), base.get(i));
        List<BlockState> upper = List.of("stone", "oak_planks", "glass", "bricks", "oak_log", "deepslate", "cobblestone",
                "sandstone", "red_sandstone", "andesite", "diorite", "granite", "white_wool", "orange_wool", "magenta_wool",
                "light_blue_wool", "yellow_wool", "lime_wool", "pink_wool", "gray_wool").stream()
                .map(name -> dev.justbuild.world.BlockResolver.parse(name).orElseThrow()).toList();
        for (int i = 0; i < upper.size(); i++) blueprint.put(new BlockPos(i % 6 * 2, 101, 6 + i / 6), upper.get(i));
        BlockPos chestAt = new BlockPos(-2, 100, 3);
        c.getInput().resizeWindow(854, 480);
        int originalScale = c.computeOnClient(mc -> mc.options.guiScale().get());
        c.runOnClient(mc -> { mc.options.guiScale().set(2); mc.resizeGui(); });
        try (var world = c.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            String name = c.computeOnClient(mc -> mc.player.getName().getString());
            world.getServer().runCommand("gamemode creative " + name);
            world.getServer().runCommand("tp " + name + " 4.5 100 -3 0 25");
            world.getServer().runCommand("time set day");
            world.getServer().runOnServer(server -> {
                for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(-8, 99, -8), new BlockPos(16, 105, 16)))
                    server.overworld().setBlock(pos, pos.getY() == 99 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                server.overworld().setBlock(chestAt, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                var chest = (ChestBlockEntity) server.overworld().getBlockEntity(chestAt);
                chest.setItem(0, new ItemStack(Items.STONE, 8)); chest.setItem(1, new ItemStack(Items.OAK_PLANKS, 64));
                var player = server.getPlayerList().getPlayers().getFirst(); player.getInventory().clearContent();
                player.getInventory().setItem(0, new ItemStack(Items.DIRT, 1));
                var project = new ConstructionProject(UUID.randomUUID(), player.getUUID(), "Mixed-material workshop", Level.OVERWORLD, blueprint, ConstructionProject.Mode.COBBLE);
                project.chest = chestAt;
                try { JustBuildMod.manager().construction().start(player, project); }
                catch (java.io.IOException e) { throw new AssertionError(e); }
                JustBuildMod.manager().construction().syncNow(player);
            });
            world.getServer().runCommand("gamemode survival " + name);
            c.waitFor(mc -> ConstructionClient.active() && ConstructionClient.state().layerMaterials().size() == 6
                    && ConstructionClient.state().status().equals("Paused") && mc.player.getMainHandItem().is(Items.DIRT), 200);
            check(c.computeOnClient(mc -> material("Stone").need() == 5 && material("Stone").layerNeed() == 4
                    && material("Stone").chest() == 8 && material("Oak Slab").need() == 2 && material("Oak Slab").layerNeed() == 2
                    && material("Deepslate").need() == 1 && material("Deepslate").layerNeed() == 0), "Layer and whole-build costs were not distinguished");
            c.waitTicks(10); c.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
            c.takeScreenshot("construction-layer-materials-hud");
            c.getInput().pressKey(InputConstants.KEY_RSHIFT); c.waitForScreen(ConstructionScreen.class);
            c.runOnClient(mc -> checkLayout(mc.gui.screen()));
            c.takeScreenshot("construction-whole-build-materials");
            c.clickScreenButton("Next"); c.takeScreenshot("construction-whole-build-materials-next"); c.clickScreenButton("Previous");
            c.clickScreenButton("Ghosts: layer"); c.waitTicks(10);
            check(c.computeOnClient(mc -> ConstructionClient.state().materials().size() == 21 && ConstructionClient.state().layerMaterials().size() == 6),
                    "Ghost layer toggle changed material scope");
            c.clickScreenButton("Materials"); c.waitForScreen(ConstructionMaterialsScreen.class);
            c.runOnClient(mc -> checkLayout(mc.gui.screen()));
            c.takeScreenshot("construction-expanded-materials"); c.clickScreenButton("Next");
            c.runOnClient(mc -> {
                EditBox field = search(mc.gui.screen()); field.setValue("minecraft:stone"); mc.gui.screen().setFocused(field);
                check(((ConstructionMaterialsScreen) mc.gui.screen()).displayedMaterials().size() == 1, "Search did not filter by item id");
            });
            c.clickScreenButton("Show: all");
            check(c.computeOnClient(mc -> ((ConstructionMaterialsScreen) mc.gui.screen()).displayedMaterials().isEmpty()), "Supplied stone appeared as missing");
            c.runOnClient(mc -> { var field = search(mc.gui.screen()); mc.gui.screen().setFocused(field); field.moveCursorTo(5, false); });
            world.getServer().runOnServer(server -> ((ChestBlockEntity) server.overworld().getBlockEntity(chestAt)).setItem(0, new ItemStack(Items.STONE, 1)));
            c.waitFor(mc -> ((ConstructionMaterialsScreen) mc.gui.screen()).displayedMaterials().size() == 1, 100);
            check(c.computeOnClient(mc -> search(mc.gui.screen()).getValue().equals("minecraft:stone")
                    && search(mc.gui.screen()).getCursorPosition() == 5 && mc.gui.screen().getFocused() == search(mc.gui.screen())), "Live counts reset the search draft or cursor");
            c.takeScreenshot("construction-materials-search-missing");
            world.getServer().runOnServer(server -> ((ChestBlockEntity) server.overworld().getBlockEntity(chestAt)).setItem(0, new ItemStack(Items.STONE, 8)));
            c.waitFor(mc -> ((ConstructionMaterialsScreen) mc.gui.screen()).displayedMaterials().isEmpty(), 100);
            c.runOnClient(mc -> search(mc.gui.screen()).setValue("")); c.clickScreenButton("Show: missing");
            c.getInput().resizeWindow(1280, 800); c.waitTicks(5); c.runOnClient(mc -> checkLayout(mc.gui.screen()));
            c.takeScreenshot("construction-materials-large-window"); c.clickScreenButton("Back");
            c.takeScreenshot("construction-project-large-window");
            c.getInput().resizeWindow(640, 480); c.waitTicks(5); c.runOnClient(mc -> checkLayout(mc.gui.screen()));
            c.takeScreenshot("construction-materials-small-window");
            c.clickScreenButton("Materials"); c.runOnClient(mc -> checkLayout(mc.gui.screen()));
            c.takeScreenshot("construction-expanded-small-window"); c.clickScreenButton("Back");
            c.getInput().resizeWindow(854, 480); c.waitTicks(5); c.clickScreenButton("Keep building");
            world.getServer().runOnServer(server -> server.overworld().setBlock(new BlockPos(0, 100, 0), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS));
            c.waitFor(mc -> material("Stone").layerNeed() == 3 && material("Stone").need() == 4, 100);
            world.getServer().runOnServer(server -> blueprint.forEach((pos, state) -> {
                if (pos.getY() == 100) server.overworld().setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }));
            c.waitFor(mc -> ConstructionClient.state().layer() == 101 && ConstructionClient.state().layerMaterials().size() == 20, 100);
            check(c.computeOnClient(mc -> material("Stone").need() == 1 && material("Stone").layerNeed() == 1
                    && ConstructionClient.state().materials().stream().noneMatch(m -> m.name().equals("Oak Slab"))), "Completed layer still appeared in requirements");
            c.takeScreenshot("construction-next-layer-materials");
            world.getServer().runOnServer(server -> blueprint.forEach((pos, state) -> server.overworld().setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)));
            c.waitFor(mc -> ConstructionClient.state().materials().isEmpty() && ConstructionClient.state().layerMaterials().isEmpty(), 100);
            check(world.getServer().computeOnServer(server -> ((ChestBlockEntity) server.overworld().getBlockEntity(chestAt)).getItem(0).getCount() == 8),
                    "Viewing material requirements spent chest supplies");
            c.takeScreenshot("construction-materials-complete");
            c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild construction finish"));
            c.waitFor(mc -> !ConstructionClient.active(), 100);
        } finally { c.runOnClient(mc -> { mc.options.guiScale().set(originalScale); mc.resizeGui(); }); }
    }
    private static EditBox search(Screen screen) {
        return screen.children().stream().filter(c -> c instanceof EditBox).map(c -> (EditBox) c).findFirst().orElseThrow();
    }
    static void checkLayout(Screen screen) {
        var widgets = screen.children().stream().filter(c -> c instanceof AbstractWidget w && w.visible).map(c -> (AbstractWidget) c).toList();
        for (var w : widgets) {
            check(w.getX() >= 0 && w.getRight() <= screen.width && w.getY() >= 0 && w.getBottom() <= screen.height,
                    "Control outside screen: " + w.getMessage().getString());
            for (var other : widgets) if (other != w)
                check(w.getX() >= other.getRight() || w.getRight() <= other.getX() || w.getY() >= other.getBottom() || w.getBottom() <= other.getY(),
                        "Overlapping controls: " + w.getMessage().getString() + " and " + other.getMessage().getString());
        }
    }
    private static ConstructionClient.Material material(String name) {
        return ConstructionClient.state().materials().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
