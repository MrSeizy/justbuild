package dev.justbuild.client;

import dev.justbuild.net.GhostPreviewPayload;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

/**
 * Previews at the largest sizes players reach, in view: 100,000 removals used to overflow Minecraft's vertex buffer
 * and crash the game, and receiving 250,000 cells used to freeze it for over 40 seconds.
 */
public final class LargePreviewGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            String player = context.computeOnClient(mc -> mc.player.getName().getString());
            BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
            // Above the box's near edge, looking down into it, so it is on screen and within marker range.
            // Spectators have no gravity, so the player stays above the box instead of falling to their death.
            world.getServer().runCommand("gamemode spectator " + player);
            world.getServer().runCommand("tp " + player + " " + (at.getX() + 0.5) + " " + (at.getY() + 40) + " "
                    + (at.getZ() - 20.5) + " 0 35");
            context.waitTicks(20);
            String dimension = context.computeOnClient(mc -> mc.level.dimension().identifier().toString());
            show(context, box(dimension, at, 100_000, GhostPreviewPayload.REMOVE), "removals");
            show(context, box(dimension, at, 250_000, GhostPreviewPayload.ADD), "additions");
            // A thin wall has no enclosed removals to skip, so every cell needs an outline and the cap applies. Centered
            // under the player, every cell is within the 128-block outline range, so the note counts all 51,200.
            List<GhostPreviewPayload.Cell> slab = new ArrayList<>();
            for (int y = 0; y < 2; y++) for (int x = 0; x < 160; x++) for (int z = 0; z < 160; z++)
                slab.add(new GhostPreviewPayload.Cell(at.offset(x - 80, y, z - 100), 0, GhostPreviewPayload.REMOVE));
            show(context, new GhostPreviewPayload(dimension, List.of("minecraft:stone"), slab, slab.size()), "thin removal");
            String note = String.format(java.util.Locale.ROOT, "Outlining %,d of %,d", GhostPreview.MAX_MARKERS, slab.size());
            if (!context.computeOnClient(mc -> ClientState.lines().stream().anyMatch(l -> l.getString().contains(note))))
                throw new AssertionError("no note that only part of the removals are outlined: expected \"" + note + "\"");
            mixedAndReload(context, dimension, at);
            context.runOnClient(mc -> GhostPreview.clear());
        }
    }

    private static void mixedAndReload(ClientGameTestContext context, String dimension, BlockPos at) {
        List<GhostPreviewPayload.Cell> cells = new ArrayList<>();
        var palette = List.of("minecraft:stone", "minecraft:oak_planks", "minecraft:oak_stairs", "minecraft:glass", "minecraft:water");
        for (int y = 0; y < 4; y++) for (int x = -18; x < 18; x++) for (int z = 0; z < 20; z++)
            cells.add(new GhostPreviewPayload.Cell(at.offset(x, y, z), Math.floorMod(x + z, palette.size()),
                    (byte) Math.floorMod(x, 3)));
        var payload = new GhostPreviewPayload(dimension, palette, cells, cells.size());
        context.runOnClient(mc -> { GhostPreview.receive(payload); FloorPreview.receive(new dev.justbuild.net.SectionPreviewPayload(payload)); FloorPreview.lower(); });
        context.waitTicks(10); context.takeScreenshot("preview-mixed-floor-slice");
        for (GhostPreview.Filter filter : GhostPreview.Filter.values()) {
            context.runOnClient(mc -> GhostPreview.filter(filter)); context.waitTicks(5);
        }
        context.runOnClient(mc -> { GhostPreview.filter(GhostPreview.Filter.ALL); FloorPreview.all(); });
        context.waitTicks(5);
        Object oldModel = context.computeOnClient(mc -> cachedModel());
        var reload = context.computeOnClient(mc -> mc.reloadResourcePacks());
        context.waitFor(mc -> reload.isDone(), 1200);
        reload.join(); context.waitTicks(10);
        if (context.computeOnClient(mc -> cachedModel()) == oldModel) throw new AssertionError("resource reload retained stale preview models");
        context.takeScreenshot("preview-mixed-after-reload");
    }

    private static Object cachedModel() {
        try {
            var field = GhostPreview.class.getDeclaredField("models"); field.setAccessible(true);
            var models = (java.util.Map<?, ?>) field.get(null);
            return models.values().stream().findFirst().orElseThrow(() -> new AssertionError("no mixed preview models were rendered"));
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static GhostPreviewPayload box(String dimension, BlockPos at, int size, byte kind) {
        List<GhostPreviewPayload.Cell> cells = new ArrayList<>(size);
        for (int i = 0; i < size; i++) cells.add(new GhostPreviewPayload.Cell(at.offset(-48 + i % 96, i / 9216, (i / 96) % 96), 0, kind));
        return new GhostPreviewPayload(dimension, List.of("minecraft:stone"), cells, size);
    }

    /** A crash while drawing fails the test by itself; a receive this slow means a quadratic copy is back. */
    private static void show(ClientGameTestContext context, GhostPreviewPayload payload, String what) {
        long millis = context.computeOnClient(mc -> { long t = System.nanoTime(); GhostPreview.receive(payload); return (System.nanoTime() - t) / 1_000_000; });
        if (millis > 5_000) throw new AssertionError("receiving " + payload.cells().size() + " " + what + " took " + millis + " ms");
        context.waitTicks(40);
        context.takeScreenshot("large-preview-" + what);
        context.runOnClient(mc -> GhostPreview.clear());
        context.waitTicks(5);
    }
}
