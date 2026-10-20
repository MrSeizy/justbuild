package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.net.GhostPreviewPayload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ChangePreviewTest {
    @BeforeAll static void bootstrap() { McBootstrap.init(); }

    @Test void distinguishesAddReplaceRemoveAndIgnoresUnchangedCells() {
        Map<BlockPos, BlockState> next = new LinkedHashMap<>();
        next.put(new BlockPos(0, 64, 0), Blocks.OAK_STAIRS.defaultBlockState());
        next.put(new BlockPos(1, 64, 0), Blocks.OAK_STAIRS.defaultBlockState());
        next.put(new BlockPos(2, 64, 0), Blocks.AIR.defaultBlockState());
        next.put(new BlockPos(3, 64, 0), Blocks.STONE.defaultBlockState());
        var preview = ChangePreview.create("minecraft:overworld", next,
                p -> p.getX() == 0 ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState());
        assertEquals(3, preview.total());
        assertEquals(2, preview.palette().size());
        assertEquals(List.of(GhostPreviewPayload.ADD, GhostPreviewPayload.REPLACE, GhostPreviewPayload.REMOVE),
                preview.cells().stream().map(GhostPreviewPayload.Cell::kind).toList());
        assertEquals(preview.cells().get(0).state(), preview.cells().get(1).state());
        assertEquals("minecraft:stone", preview.palette().get(preview.cells().get(2).state()));
        assertEquals(new ChangePreview.Counts(1, 1, 1), ChangePreview.counts(next,
                p -> p.getX() == 0 ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState()));
    }

    /** Clients that register the large preview get every changed cell up to maxBlocks; others keep the 100,000 cap. */
    @Test void largePreviewsCarryEveryCellUpToTheCapTheServerChose() {
        Map<BlockPos, BlockState> next = new LinkedHashMap<>();
        for (int i = 0; i < 250_000; i++) next.put(new BlockPos(i % 96, 64 + i / 9216, (i / 96) % 96), Blocks.STONE.defaultBlockState());
        var large = ChangePreview.create("minecraft:overworld", next, p -> Blocks.AIR.defaultBlockState(), 250_000);
        assertEquals(250_000, large.cells().size());
        var legacy = ChangePreview.create("minecraft:overworld", next, p -> Blocks.AIR.defaultBlockState(), GhostPreviewPayload.MAX_CELLS);
        assertEquals(GhostPreviewPayload.MAX_CELLS, legacy.cells().size());
        assertEquals(250_000, legacy.total(), "a capped preview still reports the full count, so the player is told");
        assertEquals(new ChangePreview.Counts(250_000, 0, 0), ChangePreview.counts(next, p -> Blocks.AIR.defaultBlockState()));

        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
                net.minecraft.core.RegistryAccess.EMPTY);
        dev.justbuild.net.LargeGhostPreviewPayload.CODEC.encode(buf, new dev.justbuild.net.LargeGhostPreviewPayload(large));
        assertTrue(buf.readableBytes() <= dev.justbuild.net.LargeGhostPreviewPayload.MAX_BYTES, buf.readableBytes() + " bytes");
        var copy = new net.minecraft.network.RegistryFriendlyByteBuf(buf.copy(), net.minecraft.core.RegistryAccess.EMPTY);
        assertEquals(large, dev.justbuild.net.LargeGhostPreviewPayload.CODEC.decode(buf).preview());
        // An older client decodes with the legacy limit, which is why the server only sends this type to clients that register it.
        assertThrows(IllegalArgumentException.class, () -> GhostPreviewPayload.CODEC.decode(copy));
    }

    @Test void rejectsMalformedPayloadsBeforeRendering() {
        assertThrows(IllegalArgumentException.class, () -> new GhostPreviewPayload("minecraft:overworld", List.of("stone"),
                List.of(new GhostPreviewPayload.Cell(BlockPos.ZERO, 1, GhostPreviewPayload.ADD)), 1));
        assertThrows(IllegalArgumentException.class, () -> new GhostPreviewPayload("minecraft:overworld", List.of("stone"),
                List.of(new GhostPreviewPayload.Cell(BlockPos.ZERO, 0, (byte) 9)), 1));
        assertThrows(IllegalArgumentException.class, () -> new GhostPreviewPayload("minecraft:overworld", List.of("stone"),
                List.of(new GhostPreviewPayload.Cell(BlockPos.ZERO, 0, GhostPreviewPayload.ADD)), 0));
    }
}
