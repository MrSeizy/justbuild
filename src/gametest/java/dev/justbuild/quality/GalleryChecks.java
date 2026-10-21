package dev.justbuild.quality;

import java.nio.file.Path;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Checks and cleanup confined to disposable gallery worlds. */
final class GalleryChecks {
    private GalleryChecks() {}

    static int bridgeRailCells(Function<BlockPos, BlockState> state, BlockPos at) {
        int rails = 0;
        for (int x = 0; x <= 12; x++) for (int z : new int[] {-2, 2}) {
            if (state.apply(at.offset(x, 1, z)).is(Blocks.OAK_FENCE)) rails++;
        }
        return rails;
    }

    static void clearDroppedItems(ServerLevel level) {
        // Clearing blocks can drop chest contents; items can also fall outside the next scene's bounds.
        level.getEntities(EntityTypes.ITEM, item -> true).forEach(Entity::discard);
    }

    static Path screenshot(ClientGameTestContext context, TestSingleplayerContext world, String name) {
        world.getServer().runOnServer(server -> clearDroppedItems(server.overworld()));
        context.waitTicks(2); // Let removal packets reach the client before capturing the view.
        return context.takeScreenshot(name);
    }
}
