package dev.justbuild.quality;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.justbuild.plan.*;
import dev.justbuild.world.CastleRouteProbe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Opt-in large scene. Its fixture calibrates the harness; live runs test the provider. */
final class CastleScene {
    static final int SIZE = siteSize(), CENTER = SIZE / 2;
    static final BlockPos MIN = new BlockPos(0, -2, -5), MAX = new BlockPos(SIZE - 1, 30, SIZE - 1);
    static final BlockPos PREP_MIN = new BlockPos(-8, -3, -10), PREP_MAX = new BlockPos(SIZE + 7, 36, SIZE + 7);
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    static final String PROMPT = (SIZE == 30 ? "Build an impressive" : "Build a large, impressive") + " medieval castle occupying roughly " + (SIZE == 30 ? 26 : 51) + " by " + (SIZE == 30 ? 26 : 51) + " blocks within a " + SIZE + " by " + SIZE + " site. "
            + "Make the tallest silhouette 22 to 30 blocks high. Use four substantial corner towers, a defensible curtain wall with crenellations and accessible wall walks, "
            + "a distinctive north gatehouse near x=" + CENTER + ", an open courtyard, and a dominant multi-storey keep. Give the keep at least three genuinely usable, furnished indoor levels: "
            + "a great hall, living quarters and a library or council chamber. Include a kitchen or workshop and thoughtful courtyard landscaping. "
            + "Choose a coherent stone, timber and roof palette, strong proportions, facade depth, windows, roof hierarchy and restrained accents. "
            + "You choose the architecture and room layout; make it feel like a convincing inhabited castle rather than a collection of empty boxes. "
            + "Ordinary walking and stairs must connect the north approach, courtyard, all three indoor levels, all four upper tower rooms and the wall walks. "
            + "No ladders, parkour or flying as the primary route; provide generous headroom, landings and protected edges. "
            + "Keep the courtyard open to the sky. Build on flat ground at y=-1, with a three-wide approach from the north at x=" + (CENTER - 1) + ".." + (CENTER + 1) + ",z=-5 into the gate. "
            + "Use the available operation budget for architecture and connected interiors, not just repeated ornament. Return the complete castle.";

    private static int siteSize() {
        int size = Integer.parseInt(System.getenv().getOrDefault("JUSTBUILD_GALLERY_CASTLE_SIZE", "55"));
        if (size != 30 && size != 55) throw new IllegalArgumentException("Castle site size must be 30 or 55");
        return size;
    }

    static void prepare(ServerLevel level, BlockPos at) {
        for (BlockPos p : BlockPos.betweenClosed(at.offset(PREP_MIN), at.offset(PREP_MAX))) {
            int y = p.getY() - at.getY();
            level.setBlock(p, y < -1 ? Blocks.STONE.defaultBlockState() : y == -1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                    : Blocks.AIR.defaultBlockState(), FLAGS);
        }
        level.setBlock(at.offset(SIZE + 5, 0, SIZE + 5), Blocks.DIAMOND_BLOCK.defaultBlockState(), FLAGS);
    }

    static void inspect(ServerLevel level, BlockPos at, JsonObject row) {
        int minX = 100, minZ = 100, maxX = -100, maxZ = -100, top = -1, solid = 0;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(MIN.getX(), 0, MIN.getZ()), at.offset(MAX))) {
            var state = level.getBlockState(p); if (state.isAir()) continue;
            int x = p.getX() - at.getX(), z = p.getZ() - at.getZ();
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            top = Math.max(top, p.getY() - at.getY()); solid++;
        }
        int minimum = SIZE == 30 ? 26 : 45;
        CastleRouteProbe.check(row, "Castle scale", maxX - minX + 1 >= minimum && maxZ - minZ + 1 >= minimum && top >= 21,
                (maxX - minX + 1) + "×" + (maxZ - minZ + 1) + " footprint; " + (top + 1) + " blocks above ground; " + solid + " non-air cells.");
        CastleRouteProbe.check(row, "Protected world marker", level.getBlockState(at.offset(SIZE + 5, 0, SIZE + 5)).is(Blocks.DIAMOND_BLOCK),
                "The world marker outside the selected site is unchanged.");
        CastleRouteProbe.inspect(level, at, row, SIZE);
    }

    static void frontCamera(ClientGameTestContext c, TestSingleplayerContext world, String player, BlockPos at) {
        if (SIZE == 30) camera(c, world, player, at, -16, 29, -18, CENTER, 10, CENTER);
        else camera(c, world, player, at, -22, 38, -24, CENTER, 10, CENTER);
    }

    static void camera(ClientGameTestContext c, TestSingleplayerContext world, String player, BlockPos at,
            double x, double y, double z, double tx, double ty, double tz) {
        double dx = tx - x, dy = ty - y, dz = tz - z;
        double yaw = Math.toDegrees(Math.atan2(-dx, dz)), pitch = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        world.getServer().runCommand("time set day"); world.getServer().runCommand("weather clear");
        world.getServer().runCommand("tp " + player + " " + (at.getX() + x) + " " + (at.getY() + y) + " " + (at.getZ() + z) + " " + yaw + " " + pitch);
        c.runOnClient(mc -> { mc.setScreen(null); mc.options.hideGui = true; }); c.waitTicks(20);
        world.getClientLevel().waitForChunksRender();
    }

    static void capture(ClientGameTestContext c, TestSingleplayerContext world, String player, BlockPos at,
            Path output, String key, JsonObject row) throws java.io.IOException {
        var views = new JsonArray(); row.add("views", views);
        shot(c, world, player, at, output, key, views, "rear", "Rear exterior", SIZE == 30 ? 46 : 76, SIZE == 30 ? 29 : 38, SIZE == 30 ? 48 : 78, CENTER, 10, CENTER);
        shot(c, world, player, at, output, key, views, "gate", "North gate", CENTER, 6, -15, CENTER, 9, CENTER - 5);
        shot(c, world, player, at, output, key, views, "above", "Overhead", CENTER, SIZE == 30 ? 55 : 85, CENTER, CENTER, 0, CENTER);
        // Cutaways are explicitly labeled. Restore exact states before any other checks or source restoration.
        for (int cut : SIZE == 30 ? new int[] {5, 6, 10, 12, 15} : new int[] {6, 12}) {
            Map<BlockPos, BlockState> removed = world.getServer().computeOnServer(server -> {
                Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
                for (BlockPos mutable : BlockPos.betweenClosed(at.offset(MIN.getX(), cut, MIN.getZ()), at.offset(MAX))) {
                    BlockPos p = mutable.immutable(); var state = server.overworld().getBlockState(p);
                    if (!state.isAir()) { blocks.put(p, state); server.overworld().setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS); }
                }
                return blocks;
            });
            try { shot(c, world, player, at, output, key, views, "cut-" + cut, "Cutaway below y=" + cut, CENTER, SIZE == 30 ? 48 : 70, CENTER, CENTER, 0, CENTER); }
            finally { world.getServer().runOnServer(server -> removed.forEach((p, state) -> server.overworld().setBlock(p, state, FLAGS))); }
        }
    }

    private static void shot(ClientGameTestContext c, TestSingleplayerContext world, String player, BlockPos at,
            Path output, String key, JsonArray views, String suffix, String label, double x, double y, double z,
            double tx, double ty, double tz) throws java.io.IOException {
        camera(c, world, player, at, x, y, z, tx, ty, tz);
        String file = key + "-" + suffix + ".png"; Files.copy(c.takeScreenshot("gallery-" + key + "-" + suffix), output.resolve(file));
        var view = new JsonObject(); view.addProperty("label", label); view.addProperty("image", file); views.add(view);
    }

    static BuildPlan fixture() {
        if (SIZE == 30) return compactFixture();
        var ops = new ArrayList<Operation>();
        fill(ops, "stone_bricks", 2, -1, 2, 52, -1, 52);
        fill(ops, "stone_bricks", 26, -1, -5, 28, -1, 2);
        for (int x : new int[] {2, 50}) fill(ops, "stone_bricks", x, 0, 2, x + 2, 8, 52);
        for (int z : new int[] {2, 50}) fill(ops, "stone_bricks", 2, 0, z, 52, 8, z + 2);
        fill(ops, "air", 25, 0, 2, 29, 4, 4);
        for (int x : new int[] {2, 42}) for (int z : new int[] {2, 42}) {
            ops.add(Operation.fill("stone_bricks", new Cell(x, -1, z), new Cell(x + 10, 19, z + 10), true));
            fill(ops, "stone_bricks", x, 8, z, x + 10, 8, z + 10);
            fill(ops, "air", x, 9, z + 1, x + 10, 11, z + 2);
            fill(ops, "air", x + 1, 9, z, x + 2, 11, z + 10);
            fill(ops, "air", x, 9, z + 8, x + 10, 11, z + 9);
            fill(ops, "air", x + 8, 9, z, x + 9, 11, z + 10);
            fill(ops, "stone_bricks", x, 20, z, x, 22, z);
        }
        // An open flight from the courtyard to the north wall walk.
        for (int step = 0; step < 9; step++) fill(ops, "stone_brick_stairs[facing=north]", 15, step, 14 - step, 17, step, 14 - step);
        fill(ops, "stone_bricks", 15, 8, 5, 17, 8, 5);
        ops.add(Operation.fill("stone_bricks", new Cell(18, -1, 28), new Cell(36, 18, 47), true));
        fill(ops, "air", 25, 0, 28, 29, 3, 28);
        for (int y : new int[] {5, 11}) fill(ops, "oak_planks", 19, y, 29, 35, y, 46);
        for (int floor = 0; floor < 2; floor++) {
            int x = 20 + floor * 6, y = floor * 6;
            fill(ops, "air", x, y + 5, 38, x + 2, y + 5, 43);
            for (int step = 0; step < 6; step++) fill(ops, "oak_stairs[facing=south]", x, y + step, 39 + step, x + 2, y + step, 39 + step);
        }
        for (int y : new int[] {0, 6, 12}) {
            ops.add(Operation.set("crafting_table", new Cell(34, y, 45)));
            fill(ops, "bookshelf", 34, y, 39, 34, y + 1, 41);
        }
        return new BuildPlan("Castle harness fixture", "A calibration layout, not provider output or an aesthetic reference.", ops);
    }

    private static BuildPlan compactFixture() {
        var ops = new ArrayList<Operation>();
        fill(ops, "stone_bricks", 2, -1, 2, 27, -1, 27);
        fill(ops, "stone_bricks", 14, -1, -5, 16, -1, 2);
        for (int x : new int[] {2, 25}) fill(ops, "stone_bricks", x, 0, 2, x + 2, 8, 27);
        for (int z : new int[] {2, 25}) fill(ops, "stone_bricks", 2, 0, z, 27, 8, z + 2);
        fill(ops, "air", 14, 0, 2, 16, 4, 4);
        for (int x : new int[] {2, 21}) for (int z : new int[] {2, 21}) {
            ops.add(Operation.fill("stone_bricks", new Cell(x, -1, z), new Cell(x + 6, 19, z + 6), true));
            fill(ops, "stone_bricks", x, 8, z, x + 6, 8, z + 6);
            fill(ops, "air", x, 9, z + 1, x + 6, 11, z + 2);
            fill(ops, "air", x + 1, 9, z, x + 2, 11, z + 6);
            fill(ops, "air", x, 9, z + 4, x + 6, 11, z + 5);
            fill(ops, "air", x + 4, 9, z, x + 5, 11, z + 6);
            fill(ops, "stone_bricks", x, 20, z, x, 22, z);
        }
        for (int step = 0; step < 9; step++) fill(ops, "stone_brick_stairs[facing=north]", 10, step, 13 - step, 12, step, 13 - step);
        ops.add(Operation.fill("stone_bricks", new Cell(10, -1, 15), new Cell(19, 18, 27), true));
        fill(ops, "air", 14, 0, 15, 16, 3, 15);
        for (int y : new int[] {5, 11}) fill(ops, "oak_planks", 11, y, 16, 18, y, 26);
        for (int floor = 0; floor < 2; floor++) {
            int x = 11 + floor * 6, y = floor * 6;
            fill(ops, "air", x, y + 5, 17, x, y + 5, 22);
            for (int step = 0; step < 6; step++) fill(ops, "oak_stairs[facing=south]", x, y + step, 18 + step, x, y + step, 18 + step);
        }
        for (int y : new int[] {0, 6, 12}) {
            ops.add(Operation.set("crafting_table", new Cell(18, y, 25)));
            fill(ops, "bookshelf", 18, y, 16, 18, y + 1, 18);
        }
        return new BuildPlan("Compact castle harness fixture", "A calibration layout, not provider output or an aesthetic reference.", ops);
    }

    private static void fill(List<Operation> ops, String block, int x, int y, int z, int xx, int yy, int zz) {
        ops.add(Operation.fill(block, new Cell(x, y, z), new Cell(xx, yy, zz), false));
    }
}
