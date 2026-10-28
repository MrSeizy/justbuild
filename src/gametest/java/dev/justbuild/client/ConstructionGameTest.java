package dev.justbuild.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.justbuild.JustBuildMod;
import dev.justbuild.construction.*;
import dev.justbuild.net.ConstructionAssistPayload;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.Vec3;

/** Actual inventory, world, network, HUD and project controls; no AI account or provider calls. */
public final class ConstructionGameTest implements FabricClientGameTest {
    private static final BlockPos AT = new BlockPos(0, 100, 0), CHEST = new BlockPos(3, 100, 3);
    @Override public void runTest(ClientGameTestContext c) {
        try (TestSingleplayerContext world = c.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            String player = c.computeOnClient(mc -> mc.player.getName().getString());
            world.getServer().runCommand("gamemode creative " + player);
            world.getServer().runCommand("tp " + player + " 2.5 100 -2 0 20");
            world.getServer().runCommand("time set day");
            world.getServer().runOnServer(server -> {
                for (BlockPos pos : BlockPos.betweenClosed(AT.offset(-10, -1, -10), AT.offset(20, 7, 15)))
                    server.overworld().setBlock(pos, pos.getY() == 99 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                inventoryTransactions();
                var p = server.getPlayerList().getPlayers().getFirst(); p.getInventory().clearContent();
                p.getInventory().setItem(0, new ItemStack(Items.DIRT, 10)); p.getInventory().setItem(1, new ItemStack(Items.STONE, 10));
                server.overworld().setBlock(CHEST, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                var chest = (ChestBlockEntity) server.overworld().getBlockEntity(CHEST);
                chest.setItem(0, new ItemStack(Items.STONE, 2));
                ItemStack named = new ItemStack(Items.STONE, 8); named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep me")); chest.setItem(1, named);
                Map<BlockPos, BlockState> walls = new LinkedHashMap<>();
                for (int y = 0; y < 3; y++) for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++)
                    if (x == 0 || x == 6 || z == 0 || z == 6) walls.put(AT.offset(x, y, z), Blocks.STONE.defaultBlockState());
                start(server, walls, ConstructionProject.Mode.GUIDE, null, "Survival stone workshop");
            });
            world.getServer().runCommand("gamemode survival " + player);
            c.waitFor(mc -> ConstructionClient.active() && ConstructionClient.state().total() == 72, 200);
            c.waitTicks(25);
            check(c.computeOnClient(mc -> ConstructionClient.state().done() == 0), "Guide placed free blocks");
            c.takeScreenshot("construction-guide-hud");
            world.getServer().runOnServer(server -> server.overworld().setBlock(AT, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS));
            c.waitFor(mc -> ConstructionClient.state().done() == 1, 100);
            c.getInput().pressKey(InputConstants.KEY_RSHIFT); c.waitForScreen(ConstructionScreen.class);
            c.takeScreenshot("construction-project-guide");
            c.clickScreenButton("Assist"); c.waitTicks(6); c.clickScreenButton("Keep building");
            aim(c, Vec3.atCenterOf(AT.east()));
            c.waitFor(mc -> AT.east().equals(ConstructionClient.target(mc)), 100);
            c.takeScreenshot("construction-assist-hud");
            c.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_RIGHT, 3);
            c.waitFor(mc -> ConstructionClient.state().done() == 2, 100);
            check(world.getServer().computeOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                return server.overworld().getBlockState(AT.east()).is(Blocks.STONE)
                        && ConstructionMaterials.available(p.getInventory(), Items.STONE) == 9
                        && ConstructionMaterials.available(p.getInventory(), Items.DIRT) == 10;
            }), "Assist did not consume exactly the requested item from inventory");
            // A replay is harmless; a forged far-away target must fail range and aim checks.
            c.waitTicks(5);
            c.runOnClient(mc -> {
                ClientPlayNetworking.send(new ConstructionAssistPayload(ConstructionClient.state().id(), AT.east()));
                ClientPlayNetworking.send(new ConstructionAssistPayload(ConstructionClient.state().id(), AT.offset(6, 2, 6)));
            });
            c.waitTicks(10);
            check(world.getServer().computeOnServer(server -> ConstructionMaterials.available(server.getPlayerList().getPlayers().getFirst().getInventory(), Items.STONE) == 9
                    && server.overworld().getBlockState(AT.offset(6, 2, 6)).isAir()), "Assist accepted a replay or out-of-reach target");

            command(c, "construction pause");
            world.getServer().runCommand("tp " + player + " 3.5 100 1.5 0 25"); aim(c, Vec3.atCenterOf(CHEST));
            c.getInput().pressKey(InputConstants.KEY_RSHIFT); c.waitForScreen(ConstructionScreen.class);
            c.clickScreenButton("Cobble"); c.waitTicks(6); c.clickScreenButton("Link chest");
            c.waitFor(mc -> ConstructionClient.state().chest() != null, 100);
            c.clickScreenButton("Materials"); c.waitForScreen(ConstructionMaterialsScreen.class);
            c.takeScreenshot("construction-materials"); c.clickScreenButton("Back");
            c.takeScreenshot("construction-project-cobble");
            world.getServer().runOnServer(server -> server.overworld().setBlock(AT.east(2), Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS));
            c.clickScreenButton("Resume"); c.waitTicks(6); c.clickScreenButton("Keep building");
            // An obstruction is stepped past, not waited on: both plain stones go into the next wall cells.
            c.waitFor(mc -> ConstructionClient.state().done() == 4 && ConstructionClient.state().status().startsWith("Waiting for Stone"), 400);
            check(world.getServer().computeOnServer(server -> chestCount(server) == 0 && server.overworld().getBlockState(AT.east(2)).is(Blocks.DIAMOND_BLOCK)
                    && server.overworld().getBlockState(AT.east(4)).is(Blocks.STONE)), "Cobble overwrote an obstruction or used free blocks or customized supplies");
            check(c.computeOnClient(mc -> AT.east(2).equals(ConstructionClient.state().blocked())
                    && ConstructionClient.state().status().endsWith("1 blocked")), "The blocked cell was not reported");
            c.takeScreenshot("construction-cobble-needs-supplies");
            world.getServer().runOnServer(server -> server.overworld().setBlock(AT.east(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS));
            command(c, "construction pause");
            world.getServer().runOnServer(server -> {
                var chest = (ChestBlockEntity) server.overworld().getBlockEntity(CHEST);
                chest.setItem(0, new ItemStack(Items.STONE, 64)); chest.setItem(2, new ItemStack(Items.STONE, 36));
            });
            c.waitTicks(25); check(c.computeOnClient(mc -> ConstructionClient.state().done() == 4), "Paused Cobble kept building");
            command(c, "construction resume");
            c.waitFor(mc -> ConstructionClient.state().done() >= 12, 500);
            c.takeScreenshot("construction-cobble-wall-course");
            try { c.waitFor(mc -> ConstructionClient.state().done() == 72, 2200); }
            catch (AssertionError e) { c.takeScreenshot("construction-incomplete"); throw new AssertionError("Incomplete construction: " + c.computeOnClient(mc -> ConstructionClient.state()), e); }
            check(world.getServer().computeOnServer(server -> chestCount(server) == 32
                    && ((ChestBlockEntity) server.overworld().getBlockEntity(CHEST)).getItem(1).getCount() == 8), "Wall material accounting was incorrect");
            command(c, "construction finish");
            check(!c.computeOnClient(mc -> ConstructionClient.active()), "Closing a project kept its client state");
            check(world.getServer().computeOnServer(server -> server.overworld().getBlockState(AT.above(2)).is(Blocks.STONE)), "Closing removed placed blocks");

            // Paired and stacked placements use their true survival costs, including exact slab state.
            world.getServer().runOnServer(server -> {
                var chest = (ChestBlockEntity) server.overworld().getBlockEntity(CHEST); chest.clearContent();
                chest.setItem(0, new ItemStack(Items.OAK_DOOR, 1)); chest.setItem(1, new ItemStack(Items.OAK_SLAB, 2));
                chest.setItem(2, new ItemStack(Items.TORCH, 1));
                BlockPos door = AT.east(10); Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
                cells.put(door, Blocks.OAK_DOOR.defaultBlockState());
                cells.put(door.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
                cells.put(door.east(), Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE));
                cells.put(door.east().above(), Blocks.TORCH.defaultBlockState());
                start(server, cells, ConstructionProject.Mode.COBBLE, CHEST, "Door and stacked materials");
            });
            c.waitFor(mc -> ConstructionClient.active() && ConstructionClient.state().done() == 4, 500);
            check(world.getServer().computeOnServer(server -> {
                var chest = (ChestBlockEntity) server.overworld().getBlockEntity(CHEST);
                return chest.isEmpty() && server.overworld().getBlockState(AT.east(11)).getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE;
            }), "Door, slab or torch placement did not match its item cost");
            command(c, "construction finish");
            safetyChecks(c, world, player);
        }
        c.waitTicks(3); check(!c.computeOnClient(mc -> ConstructionClient.active()), "Disconnect retained construction state");
    }
    private static void safetyChecks(ClientGameTestContext c, TestSingleplayerContext world, String player) {
        BlockPos protectedChest = AT.offset(10, 0, 3);
        world.getServer().runOnServer(server -> {
            var supply = (ChestBlockEntity) server.overworld().getBlockEntity(CHEST); supply.setItem(0, new ItemStack(Items.STONE, 10));
            server.overworld().setBlock(protectedChest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            ((ChestBlockEntity) server.overworld().getBlockEntity(protectedChest)).setItem(0, new ItemStack(Items.DIAMOND, 5));
            start(server, Map.of(protectedChest, Blocks.STONE.defaultBlockState()), ConstructionProject.Mode.COBBLE, CHEST, "Keep player belongings");
        });
        c.waitFor(mc -> ConstructionClient.state() != null && ConstructionClient.state().status().startsWith("Clear the obstruction"), 200);
        check(world.getServer().computeOnServer(server -> chestCount(server) == 10
                && ((ChestBlockEntity) server.overworld().getBlockEntity(protectedChest)).getItem(0).getCount() == 5), "A protected chest was replaced or charged for");
        command(c, "construction finish");

        BlockPos occupied = AT.offset(3, 0, 1);
        world.getServer().runCommand("tp " + player + " 3.5 100 1.5");
        world.getServer().runOnServer(server -> start(server, Map.of(occupied, Blocks.STONE.defaultBlockState()), ConstructionProject.Mode.COBBLE, CHEST, "Keep the player clear"));
        c.waitFor(mc -> ConstructionClient.state() != null && ConstructionClient.state().status().startsWith("Waiting for the work area to be clear"), 200);
        check(world.getServer().computeOnServer(server -> server.overworld().getBlockState(occupied).isAir() && chestCount(server) == 10), "Cobble buried a player");
        world.getServer().runCommand("tp " + player + " 3.5 100 4.5");
        c.waitFor(mc -> ConstructionClient.state().done() == 1, 200); command(c, "construction finish");

        BlockPos torch = AT.offset(10, 3, 6);
        world.getServer().runOnServer(server -> {
            var supply = (ChestBlockEntity) server.overworld().getBlockEntity(CHEST); supply.clearContent(); supply.setItem(0, new ItemStack(Items.TORCH, 1));
            start(server, Map.of(torch, Blocks.TORCH.defaultBlockState()), ConstructionProject.Mode.COBBLE, CHEST, "Wait for support");
        });
        c.waitFor(mc -> ConstructionClient.state() != null && ConstructionClient.state().status().startsWith("Add the required support"), 200);
        check(world.getServer().computeOnServer(server -> server.overworld().getBlockState(torch).isAir()
                && ((ChestBlockEntity) server.overworld().getBlockEntity(CHEST)).getItem(0).getCount() == 1), "Failed support check consumed a torch");
        world.getServer().runOnServer(server -> server.overworld().setBlock(torch.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
        c.waitFor(mc -> ConstructionClient.state().done() == 1, 200); command(c, "construction finish");
        skipsAndClears(c, world);
    }
    /** A hanging lantern waits for its ceiling, grass in an empty space is cleared, and dug-in terrain is left for the player. */
    private static void skipsAndClears(ClientGameTestContext c, TestSingleplayerContext world) {
        BlockPos grass = AT.offset(12, 0, 2), dirt = AT.offset(12, 0, 3), lantern = AT.offset(13, 1, 2), ceiling = AT.offset(13, 2, 2);
        world.getServer().runOnServer(server -> {
            var level = server.overworld(); int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
            level.setBlock(grass.below(), Blocks.GRASS_BLOCK.defaultBlockState(), flags); level.setBlock(grass, Blocks.SHORT_GRASS.defaultBlockState(), flags);
            level.setBlock(dirt, Blocks.DIRT.defaultBlockState(), flags);
            var supply = (ChestBlockEntity) level.getBlockEntity(CHEST); supply.clearContent();
            supply.setItem(0, new ItemStack(Items.STONE, 1)); supply.setItem(1, new ItemStack(Items.LANTERN, 1));
            Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
            cells.put(grass, Blocks.AIR.defaultBlockState()); cells.put(dirt, Blocks.AIR.defaultBlockState());
            cells.put(lantern, Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true));
            cells.put(ceiling, Blocks.STONE.defaultBlockState());
            start(server, cells, ConstructionProject.Mode.COBBLE, null, "Hanging lantern on a grassy site");
        });
        // Link chest without aiming at one: the panel closes and the next chest the player opens becomes the supply.
        aim(c, new Vec3(3.5, 112, 4.5));
        c.getInput().pressKey(InputConstants.KEY_RSHIFT); c.waitForScreen(ConstructionScreen.class);
        c.clickScreenButton("Link chest");
        c.waitFor(mc -> mc.gui.screen() == null && ConstructionClient.state().status().startsWith("Open a chest"), 100);
        aim(c, Vec3.atCenterOf(CHEST)); c.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_RIGHT, 3);
        c.waitFor(mc -> CHEST.equals(ConstructionClient.state().chest()), 100);
        c.getInput().pressKey(InputConstants.KEY_ESCAPE); c.waitFor(mc -> mc.gui.screen() == null, 100);
        try {
            c.waitFor(mc -> ConstructionClient.state() != null && ConstructionClient.state().done() == 2
                    && ConstructionClient.state().status().startsWith("All blocks placed"), 600);
        } catch (AssertionError e) { throw new AssertionError("Cobble stalled: " + c.computeOnClient(mc -> ConstructionClient.state()), e); }
        check(world.getServer().computeOnServer(server -> server.overworld().getBlockState(grass).isAir() && server.overworld().getBlockState(dirt).is(Blocks.DIRT)
                && server.overworld().getBlockState(lantern).is(Blocks.LANTERN) && ((ChestBlockEntity) server.overworld().getBlockEntity(CHEST)).isEmpty()),
                "Cobble did not clear the plant, keep the terrain, and hang the lantern once its ceiling stood");
        check(c.computeOnClient(mc -> dirt.equals(ConstructionClient.state().blocked())), "The space to dig out was not pointed out");
        world.getServer().runOnServer(server -> server.overworld().setBlock(dirt, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS));
        c.waitFor(mc -> ConstructionClient.state().status().startsWith("Complete"), 200);
        command(c, "construction finish");
    }
    private static void start(net.minecraft.server.MinecraftServer server, Map<BlockPos, BlockState> cells,
            ConstructionProject.Mode mode, BlockPos chest, String title) {
        var player = server.getPlayerList().getPlayers().getFirst();
        var project = new ConstructionProject(UUID.randomUUID(), player.getUUID(), title, Level.OVERWORLD, cells, mode);
        project.chest = chest; project.paused = false;
        try { JustBuildMod.manager().construction().start(player, project); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
        JustBuildMod.manager().construction().sync(player);
    }
    private static int chestCount(net.minecraft.server.MinecraftServer s) {
        return ConstructionMaterials.available((ChestBlockEntity) s.overworld().getBlockEntity(CHEST), Items.STONE);
    }
    private static void aim(ClientGameTestContext c, Vec3 target) {
        c.runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, target)); c.waitTicks(5);
    }
    private static void command(ClientGameTestContext c, String command) {
        c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + command)); c.waitTicks(10);
    }
    private static void inventoryTransactions() {
        ItemStack named = new ItemStack(Items.STONE, 8); named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep me"));
        var inventory = new SimpleContainer(new ItemStack(Items.STONE, 2), named, new ItemStack(Items.STONE, 3), new ItemStack(Items.DIRT, 5));
        check(ConstructionMaterials.available(inventory, Items.STONE) == 5, "Custom stack counted as a plain building item");
        check(!ConstructionMaterials.consume(inventory, Items.STONE, 6) && inventory.getItem(0).getCount() == 2 && inventory.getItem(2).getCount() == 3,
                "Shortage partially consumed items");
        check(ConstructionMaterials.consume(inventory, Items.STONE, 4) && inventory.getItem(0).isEmpty() && inventory.getItem(2).getCount() == 1
                && inventory.getItem(1).getCount() == 8 && inventory.getItem(3).getCount() == 5, "Multi-stack purchase changed unrelated items");
        // A player's slots past the 36 storage slots hold worn armor and the offhand.
        var worn = new SimpleContainer(new ItemStack(Items.CARVED_PUMPKIN, 1), new ItemStack(Items.STONE, 1), new ItemStack(Items.CARVED_PUMPKIN, 1));
        check(ConstructionMaterials.available(worn, Items.CARVED_PUMPKIN, 2) == 1 && !ConstructionMaterials.consume(worn, Items.CARVED_PUMPKIN, 2, 2)
                && ConstructionMaterials.consume(worn, Items.CARVED_PUMPKIN, 1, 2) && worn.getItem(0).isEmpty() && worn.getItem(2).getCount() == 1,
                "Slot-limited supplies touched a slot past the limit");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
