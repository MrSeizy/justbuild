package dev.justbuild.quality;

import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Reject material-count false positives and keep scene cleanup limited to dropped items. */
public final class GalleryChecksGameTest implements FabricClientGameTest {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @Override public void runTest(ClientGameTestContext context) {
        bridgeRails();
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition().above(12));
            world.getServer().runOnServer(server -> {
                var level = server.overworld();
                var loose = new ItemEntity(level, at.getX(), at.getY(), at.getZ(), new ItemStack(Items.OAK_FENCE));
                require(level.addFreshEntity(loose), "could not spawn a dropped-item fixture");
                var stand = new ArmorStand(level, at.getX() + 4, at.getY(), at.getZ());
                require(level.addFreshEntity(stand), "could not spawn the non-item control");
                level.setBlock(at, Blocks.CHEST.defaultBlockState(), FLAGS);
                ((Container) level.getBlockEntity(at)).setItem(0, new ItemStack(Items.DIAMOND, 7));
                BlockPos oldChest = at.offset(2, 0, 0);
                level.setBlock(oldChest, Blocks.CHEST.defaultBlockState(), FLAGS);
                ((Container) level.getBlockEntity(oldChest)).setItem(0, new ItemStack(Items.GOLD_INGOT, 5));
                level.setBlock(oldChest, Blocks.AIR.defaultBlockState(), FLAGS);
                require(level.getEntities(EntityTypes.ITEM, item -> true).size() >= 2,
                        "resetting the old chest did not exercise dropped inventory");

                GalleryChecks.clearDroppedItems(level);
                require(level.getEntities(EntityTypes.ITEM, item -> true).isEmpty(), "scene cleanup retained dropped items");
                require(!stand.isRemoved() && !server.getPlayerList().getPlayers().isEmpty(), "cleanup removed a non-item entity");
                var inventory = (Container) level.getBlockEntity(at);
                require(inventory.getItem(0).is(Items.DIAMOND) && inventory.getItem(0).getCount() == 7,
                        "cleanup changed the retained chest inventory");
                // A later drop exercises screenshot cleanup independently of the scene reset.
                require(level.addFreshEntity(new ItemEntity(level, at.getX(), at.getY() + 1, at.getZ(),
                        new ItemStack(Items.OAK_PLANKS))), "could not spawn the screenshot fixture");
            });
            context.waitFor(mc -> {
                for (var entity : mc.level.entitiesForRendering()) if (entity instanceof ItemEntity) return true;
                return false;
            });
            GalleryChecks.screenshot(context, world, "gallery-cleanup-regression");
            require(context.computeOnClient(mc -> {
                for (var entity : mc.level.entitiesForRendering()) if (entity instanceof ItemEntity) return false;
                return true;
            }), "the screenshot retained a client-side dropped item");
        }
    }

    private static void bridgeRails() {
        BlockPos at = new BlockPos(17, 80, -9);
        for (int y : new int[] {0, 1, 2}) {
            Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
            for (int x = 0; x <= 12; x++) for (int z : new int[] {-2, 2})
                blocks.put(at.offset(x, y, z), Blocks.OAK_FENCE.defaultBlockState());
            require(blocks.size() >= 20, "the counterexample must meet the existing material quota");
            int rails = GalleryChecks.bridgeRailCells(p -> blocks.getOrDefault(p, Blocks.AIR.defaultBlockState()), at);
            require(rails == (y == 1 ? 26 : 0), "rails at y=" + y + " were classified incorrectly");
            if (y == 1) {
                blocks.remove(at.offset(6, 1, 2));
                blocks.put(at.offset(6, 1, 3), Blocks.OAK_FENCE.defaultBlockState());
                require(blocks.size() == 26, "the gap counterexample must retain every fence");
                require(GalleryChecks.bridgeRailCells(p -> blocks.getOrDefault(p, Blocks.AIR.defaultBlockState()), at) == 25,
                        "an extra fence outside the edge concealed a gap in the railing");
            }
        }
    }

    private static void require(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
