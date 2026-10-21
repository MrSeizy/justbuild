package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import dev.justbuild.config.ConfigStore;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Opt-in trailer footage, selected by {@code trailer/capture.py} and absent from the normal gametest entrypoints. Every
 * build on screen replays one of the maintainer's saved plans through the local fixture provider; no AI is called.
 * Frames are raw 1920×1080 screenshots at 30 fps with no captions.
 */
public final class TrailerCapture implements FabricClientGameTest {
    static final int FPS = 30, W = 1920, H = 1080;
    static final String CHICKEN_PROMPT = "build a massive robot chicken with laser eyes and rocket launchers on its shoulders";
    static final String SCORE_PROMPT = "change the score to 10 : 3";
    /** Real time: two game ticks per three frames. */
    static final double REAL = 20.0 / FPS;

    record Pose(Vec3 eye, float yaw, float pitch) { }
    interface Cue { Pose at(int frame, double u); }

    private final Path out = Path.of(System.getenv("JUSTBUILD_TRAILER_OUTPUT"));
    private final Path plans = Path.of(System.getenv("JUSTBUILD_TRAILER_PLANS"));
    private final int step = Integer.parseInt(System.getenv().getOrDefault("JUSTBUILD_TRAILER_STEP", "1"));
    private final Set<String> only = Arrays.stream(System.getenv().getOrDefault("JUSTBUILD_TRAILER_ONLY", "").split(","))
            .map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    /** "all", or one standalone scene re-recorded on its own (its manifest is clips-<scene>.json). */
    private final String scene = System.getenv().getOrDefault("JUSTBUILD_TRAILER_SCENE", "all");
    private final JsonArray clips = new JsonArray();
    private final Path configFile = FabricLoader.getInstance().getConfigDir().resolve("justbuild.json");
    private ClientGameTestContext c;
    private TestSingleplayerContext world;
    private DemoFixture ai;
    private String player, id;
    private double owed;
    private Long frozenTime;

    @Override public void runTest(ClientGameTestContext context) {
        System.setProperty("java.awt.headless", "true");
        c = context;
        try (DemoFixture fixture = new DemoFixture()) {
            ai = fixture;
            configure(4000);
            try (TestSingleplayerContext created = c.worldBuilder().setUseConsistentSettings(false)
                    .adjustSettings(s -> { s.setSeed("justbuild-cluckotron"); s.setGenerateStructures(false); }).create()) {
                world = created;
                settle();
                player = c.computeOnClient(mc -> mc.player.getName().getString());
                id = c.computeOnClient(mc -> mc.player.getUUID().toString());
                c.runOnClient(mc -> { mc.options.renderDistance().set(16); mc.options.fov().set(70); mc.options.bobView().set(false); });
                for (String command : List.of("difficulty peaceful", "weather clear 1000000", "gamemode spectator " + player))
                    world.getServer().runCommand(command);
                record();
            }
            ai.verify();
            Files.writeString(out.resolve(manifest()), clips.toString());
        } catch (Exception e) { throw new AssertionError("Trailer capture failed", e); }
    }

    private void record() throws Exception {
        BlockPos plains = world.getServer().computeOnServer(server -> {
            var found = server.overworld().findClosestBiome3d(b -> b.is(Biomes.SUNFLOWER_PLAINS) || b.is(Biomes.PLAINS),
                    BlockPos.ZERO, 6400, 32, 64);
            return found == null ? BlockPos.ZERO : found.getFirst();
        });
        System.out.println("TRAILER: plains at " + plains);
        if (scene.equals("keyboard-preview")) {
            keyboardPreview(plains);
            return;
        }
        if (scene.equals("robo-undo")) {
            robo(plains, true);
            return;
        }

        // The Cluckotron's site; acts 1-3 and 5 stay here.
        BlockPos chicken = site(plains, 0, 0, 56, 56);
        BlockPos stand = chicken.offset(5, 0, 19);
        frozenTime = null;
        dirt(chicken, stand);
        panel(chicken);
        BlockPos[] dumped = preview(chicken);
        dump(dumped[0], dumped[1], "cluckotron");
        undo(chicken);

        // Montage builds are placed between shots at full speed.
        configure(4000);
        BlockPos aliens = site(plains, 0, -150, 34, 34);
        place(aliens, 34, 34, "saucer", "build a hovering alien saucer");
        place(aliens, 34, 34, "aliens", "add an alien invasion squad");
        frozenTime = 18000L;
        shot("a6-aliens", 120, REAL, (i, u) -> dolly(aliens.offset(-20, 3, -6), aliens.offset(22, 5, -8),
                aliens.offset(-4, 4, -27), aliens.offset(14, 5, -27), u));

        BlockPos keyboard = site(plains, 150, 0, 32, 32);
        place(keyboard, 32, 32, "keyboard", "build the forgotten giant keyboard");
        frozenTime = 2500L;
        shot("a6-keyboard", 120, REAL, (i, u) -> dolly(keyboard.offset(0, 3, 6), keyboard.offset(0, 30, 14),
                keyboard.offset(0, 1, -12), keyboard.offset(0, 0, -16), u));

        BlockPos frisbee = site(plains, -110, 0, 14, 14);
        place(frisbee, 14, 14, "frisbee", "a dog leaping for a frisbee");
        frozenTime = 1200L;
        shot("a6-frisbee", 120, REAL, (i, u) -> orbit(Vec3.atCenterOf(frisbee.offset(0, 7, 0)), 17, 5, -40, 40, u));

        BlockPos stadium = site(plains, 0, 170, 50, 50);
        place(stadium, 50, 50, "stadium", "create a life sized soccer stadium");
        score(stadium);

        robo(plains, false);
    }

    /** The castle and the laser robo-chicken: "REGRET IT.", and in the robo-undo scene, "UNDO IT." */
    private void robo(BlockPos plains, boolean undo) throws Exception {
        BlockPos castle = site(plains, -170, -150, 20, 20);
        place(castle, 16, 16, "castle", "a stone castle with crimson spires");
        BlockPos robo = siteAt(castle.offset(44, 0, -91), 14, 21);
        place(robo, 13, 20, "robochicken", "a giant iron robot chicken firing lasers");
        frozenTime = 12250L;
        BlockPos between = castle.offset(22, 0, -45);
        Vec3 target = Vec3.atCenterOf(between.offset(8, 14, -16));
        if (!undo) {
            shot("a6-robo", 150, REAL, (i, u) -> {
                double a = Math.toRadians(30 + 50 * smooth(u));
                return look(Vec3.atCenterOf(between).add(Math.sin(a) * 70, 26 - 6 * u, Math.cos(a) * 70), target);
            });
            return;
        }
        // The real Undo takes the robo-chicken back off the hill, slowly enough to watch; the castle stays.
        configure(200); command("reload");
        c.runOnClient(mc -> { dev.justbuild.client.TestHud.hidden(mc, true); mc.gui.hud.getChat().clearMessages(false); });
        shot("a6-robo-undo", 105, REAL, (i, u) -> {
            if (i == 15) c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild undo"));
            if (i == 21) c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild undo confirm"));
            double a = Math.toRadians(80 + 6 * u);  // picks up where a6-robo ends
            return look(Vec3.atCenterOf(between).add(Math.sin(a) * 70, 20 - 1.5 * u, Math.cos(a) * 70), target);
        });
        c.waitFor(mc -> ClientState.placing() == null, 2400);
        configure(4000); command("reload");
    }

    /** The "PREVIEW IT." beat: the keyboard's ghost preview, filmed with the keyboard shot's crane, then discarded. */
    private void keyboardPreview(BlockPos plains) {
        BlockPos keyboard = site(plains, 150, 0, 32, 32);
        command("select clear");
        command("zone 1 " + pos(keyboard.offset(-32, -1, -32))); command("zone 2 " + pos(keyboard.offset(32, -1, 32)));
        teleport(Vec3.atCenterOf(keyboard.offset(0, 6, 38)), 180, 20);
        c.waitTicks(6);
        ai.answer(plan("keyboard"));
        command("build build the forgotten giant keyboard");
        c.waitFor(mc -> ClientState.preview() != null, 2400);
        c.runOnClient(mc -> { dev.justbuild.client.TestHud.hidden(mc, true); mc.gui.hud.getChat().clearMessages(false); });
        frozenTime = 2500L;
        shot("a6-keyboard-preview", 120, REAL, (i, u) -> dolly(keyboard.offset(0, 3, 6), keyboard.offset(0, 30, 14),
                keyboard.offset(0, 1, -12), keyboard.offset(0, 0, -16), u));
        command("cancel");
    }

    private String manifest() {
        return scene.equals("all") ? "clips.json" : "clips-" + scene + ".json";
    }

    // Act 1: the sun and moon race across the sky while one player places single dirt blocks.
    private void dirt(BlockPos chicken, BlockPos stand) {
        world.getServer().runCommand("gamemode creative " + player);
        world.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().getFirst();
            p.getInventory().setItem(0, new ItemStack(Items.DIRT, 64)); p.getInventory().setSelectedSlot(0);
        });
        c.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        teleport(Vec3.atBottomCenterOf(stand), 180, 25);
        world.getServer().runCommand("time set 23200");
        c.waitTicks(20);
        c.runOnClient(mc -> { dev.justbuild.client.TestHud.hidden(mc, false); mc.gui.hud.getChat().clearMessages(false); });
        // Ground blocks to the north-west, placed in a slow little row.
        // Within creative reach (5 blocks), with a wide lens so the racing sky fills the top of the frame.
        BlockPos[] targets = {stand.offset(-2, -1, -4), stand.offset(-1, -1, -4), stand.offset(0, -1, -4), stand.offset(1, -1, -4), stand.offset(2, -1, -4)};
        c.runOnClient(mc -> mc.options.fov().set(85));
        int each = 54;
        shot("a1-dirt", targets.length * each, REAL, (i, u) -> {
            world.getServer().runCommand("time add 300");
            int n = i / each, k = i % each;
            Vec3 eye = c.computeOnClient(mc -> mc.player.getEyePosition());
            Vec3 now = Vec3.atBottomCenterOf(targets[n]).add(0, 1, 0), before = n == 0 ? now.add(0, 0, -1) : Vec3.atBottomCenterOf(targets[n - 1]).add(0, 1, 0);
            Vec3 aim = before.lerp(now, smooth(Math.min(1, k / 14.0)));
            Pose pose = look(eye, aim);
            if (k == 22) {
                applyPose(pose, false); c.waitTick();
                if (c.computeOnClient(mc -> mc.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(targets[n])))
                    c.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
            }
            return new Pose(null, pose.yaw(), pose.pitch());
        });
        c.runOnClient(mc -> mc.options.fov().set(70));
    }

    // Act 2: the panel, the request and the press of Build, then Cobble hammering while the plan is held.
    private void panel(BlockPos chicken) throws Exception {
        command("zone 1 " + pos(chicken.offset(-24, -1, -24))); command("zone 2 " + pos(chicken.offset(24, -1, 24)));
        c.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
        frozenTime = 11600L;
        var answer = ai.answer(plan("cluckotron"), true);
        Vec3 eye = c.computeOnClient(mc -> mc.player.getEyePosition());
        Pose view = look(eye, Vec3.atCenterOf(chicken.offset(0, 6, 0)));
        int typeFrom = 18, typeTo = typeFrom + CHICKEN_PROMPT.length() * 2 / 3, click = typeTo + 22;
        shot("a2-panel", click + 16, REAL, (i, u) -> {
            if (i == 6) { c.getInput().pressKey(InputConstants.KEY_RSHIFT); c.waitForScreen(JustBuildScreen.class); c.tryClickScreenButton("Create"); }
            if (i >= typeFrom && i <= typeTo) {
                String partial = CHICKEN_PROMPT.substring(0, Math.min(CHICKEN_PROMPT.length(), (int) Math.ceil((i - typeFrom + 1) * 1.5)));
                c.runOnClient(mc -> field(mc, "Request").setValue(partial));
            }
            if (i == click) c.clickScreenButton("Build");
            if (i == click + 12) c.runOnClient(mc -> mc.gui.setScreen(null));
            return view;
        });
        c.runOnClient(mc -> dev.justbuild.client.TestHud.hidden(mc, true));
        world.getServer().runCommand("gamemode spectator " + player);
        if (!answer.started().await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("planning did not start");
        c.waitFor(mc -> BuilderBots.hammering(id) && BuilderBots.position(id) != null, 600);
        Vec3 bot = c.computeOnClient(mc -> BuilderBots.position(id));
        Vec3 toSite = Vec3.atCenterOf(chicken).subtract(bot).multiply(1, 0, 1).normalize();
        Vec3 side = new Vec3(-toSite.z, 0, toSite.x);
        shot("a2-cobble", 105, REAL, (i, u) -> {
            Vec3 at = c.computeOnClient(mc -> BuilderBots.position(id));
            Vec3 cam = at.subtract(toSite.scale(2.0)).add(side.scale(1.1 - 1.5 * smooth(u))).add(0, 1.0 + .25 * u, 0);
            return look(cam, at.add(0, .75, 0));
        });
        answer.release().countDown();
    }

    // Act 3: orbit the ghost preview, then a time-lapse of the real placement.
    private BlockPos[] preview(BlockPos chicken) {
        c.waitFor(mc -> ClientState.preview() != null, 1200);
        Vec3 center = Vec3.atCenterOf(chicken.offset(0, 14, 2));
        shot("a3-preview", 120, REAL, (i, u) -> orbit(center, 46, 6, -35, 25, u));
        var preview = c.computeOnClient(mc -> ClientState.preview());
        int cells = Math.max(1, preview.added() + preview.replaced() + preview.removed());
        int perTick = 40;
        configure(perTick);
        command("reload");
        command("confirm");
        double ticks = cells / (double) perTick, perFrame = Math.max(REAL, ticks / 260);
        System.out.println("TRAILER: " + cells + " cells, " + perFrame + " ticks per frame");
        frozenTime = 12100L;
        shot("a3-build", (int) Math.ceil(ticks / perFrame) + 45, perFrame, (i, u) -> {
            Vec3 eye = Vec3.atCenterOf(chicken).add(-6 + 10 * smooth(u), 2.0 + 2.0 * u, 36 - 8 * smooth(u));
            return look(eye, Vec3.atCenterOf(chicken).add(0, 4 + 14 * smooth(u), 0));
        });
        c.waitFor(mc -> ClientState.placing() == null && ClientState.preview() == null, 2400);
        frozenTime = 12500L;
        shot("a3-hero", 120, REAL, (i, u) -> {
            Vec3 eye = Vec3.atCenterOf(chicken).add(16 - 8 * smooth(u), 1.4, 26 - 4 * smooth(u));
            return look(eye, Vec3.atCenterOf(chicken.offset(0, 22, 0)));
        });
        return new BlockPos[]{chicken.offset(-44, -4, -44), chicken.offset(44, 44, 44)};
    }

    // Act 5: the real Undo removes the Cluckotron.
    private void undo(BlockPos chicken) {
        configure(600); command("reload");
        frozenTime = 12300L;
        Pose wide = look(Vec3.atCenterOf(chicken).add(26, 8, 50), Vec3.atCenterOf(chicken.offset(0, 15, 0)));
        applyPose(wide, true);
        int[] stage = {0};
        shot("a5-undo", 165, REAL, (i, u) -> {
            if (i == 24) { c.runOnClient(mc -> dev.justbuild.client.TestHud.hidden(mc, false)); c.getInput().pressKey(InputConstants.KEY_RSHIFT);
                c.waitForScreen(JustBuildScreen.class); c.tryClickScreenButton("Create"); }
            if (i == 44) { ReadmeDemo.chooseMaterial(c, "Undo"); c.waitFor(mc -> ClientState.undoWaiting() != null, 200); }
            if (i == 66) { c.clickScreenButton("Confirm undo"); c.runOnClient(mc -> { mc.gui.setScreen(null); dev.justbuild.client.TestHud.hidden(mc, true); }); stage[0] = 1; }
            return wide;
        });
        c.waitFor(mc -> ClientState.placing() == null, 2400);
        configure(4000); command("reload");
    }

    // Act 6: the stadium's scoreboard edited from 0 : 0 to 10 : 3 with a box selection.
    private void score(BlockPos stadium) throws Exception {
        frozenTime = 14500L;
        Vec3 board = Vec3.atCenterOf(stadium.offset(0, 10, -46));
        Pose close = look(board.add(5, -2, 26), board);
        shot("a6-score-before", 60, REAL, (i, u) -> look(board.add(5 - 2 * u, -2, 26 - 3 * u), board));
        command("box wand");
        command("box 1 " + pos(stadium.offset(-10, 6, -47))); command("box 2 " + pos(stadium.offset(9, 14, -45)));
        c.runOnClient(mc -> dev.justbuild.client.TestHud.hidden(mc, true));
        ai.answer(plan("scoreboard"));
        int typeTo = 10 + SCORE_PROMPT.length(), click = typeTo + 14;
        shot("a6-score-type", click + 10, REAL, (i, u) -> {
            if (i == 2) { c.runOnClient(mc -> dev.justbuild.client.TestHud.hidden(mc, false)); c.getInput().pressKey(InputConstants.KEY_RSHIFT);
                c.waitForScreen(JustBuildScreen.class); c.tryClickScreenButton("Create"); }
            if (i >= 10 && i <= typeTo) {
                String partial = SCORE_PROMPT.substring(0, Math.min(SCORE_PROMPT.length(), i - 9));
                c.runOnClient(mc -> field(mc, "Request").setValue(partial));
            }
            if (i == click) c.clickScreenButton("Apply");
            return close;
        });
        c.runOnClient(mc -> { mc.gui.setScreen(null); dev.justbuild.client.TestHud.hidden(mc, true); });
        c.waitFor(mc -> ClientState.preview() != null, 1200);
        command("confirm");
        shot("a6-score-after", 90, REAL, (i, u) -> look(board.add(3 - 2 * u, -2, 23 - 3 * u), board));
        c.waitFor(mc -> ClientState.placing() == null, 1200);
        command("box clear");
        shot("a6-score-wide", 120, REAL, (i, u) -> {
            Vec3 eye = Vec3.atCenterOf(stadium).add(-30 + 20 * smooth(u), 30, 62);
            return look(eye, board.add(0, -6, 10));
        });
    }

    /** Places a saved plan off-camera through the real request, preview and confirm flow, facing north. */
    private void place(BlockPos origin, int halfX, int halfZ, String plan, String request) throws Exception {
        command("select clear");
        command("zone 1 " + pos(origin.offset(-halfX, -1, -halfZ))); command("zone 2 " + pos(origin.offset(halfX, -1, halfZ)));
        teleport(Vec3.atCenterOf(origin.offset(0, 6, halfZ + 6)), 180, 20);
        c.waitTicks(6);
        ai.answer(plan(plan));
        command("build " + request);
        c.waitFor(mc -> ClientState.preview() != null, 2400);
        command("confirm");
        c.waitFor(mc -> ClientState.placing() == null && ClientState.preview() == null, 6000);
        c.waitTicks(10);
        settle();
    }

    private BlockPos site(BlockPos plains, int dx, int dz, int halfX, int halfZ) {
        int x = plains.getX() + dx, z = plains.getZ() + dz;
        teleport(new Vec3(x + .5, 200, z + .5), 180, 60);
        c.waitTicks(10);
        int ground = world.getServer().computeOnServer(server -> {
            var level = server.overworld();
            level.getChunk(x >> 4, z >> 4);
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        });
        return siteAt(new BlockPos(x, ground + 1, z), halfX, halfZ);
    }

    /** Levels the work area to grass at the origin's ground height, clearing trees and water above it. */
    private BlockPos siteAt(BlockPos origin, int halfX, int halfZ) {
        int ground = origin.getY() - 1, margin = 8;
        teleport(Vec3.atCenterOf(origin.offset(0, 20, 0)), 180, 60);
        c.waitTicks(10);
        world.getServer().runOnServer(server -> {
            var level = server.overworld();
            for (int x = -halfX - margin; x <= halfX + margin; x++) for (int z = -halfZ - margin; z <= halfZ + margin; z++) {
                int ax = origin.getX() + x, az = origin.getZ() + z;
                level.getChunk(ax >> 4, az >> 4);
                for (int y = ground - 5; y <= ground + 45; y++) {
                    BlockState state = y < ground - 2 ? null : y < ground ? Blocks.DIRT.defaultBlockState()
                            : y == ground ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    if (state == null) {
                        if (level.getBlockState(new BlockPos(ax, y, az)).isAir() || !level.getFluidState(new BlockPos(ax, y, az)).isEmpty())
                            level.setBlock(new BlockPos(ax, y, az), Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
                    } else level.setBlock(new BlockPos(ax, y, az), state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        });
        c.waitTicks(20);
        settle();
        return origin;
    }

    private void shot(String name, int frames, double ticksPerFrame, Cue cue) {
        boolean capture = only.isEmpty() || only.contains(name);
        Path dir = out.resolve(name);
        try { Files.createDirectories(dir); } catch (Exception e) { throw new AssertionError(e); }
        size();
        if (frozenTime != null) { world.getServer().runCommand("time set " + frozenTime); c.waitTicks(2); }
        // Render the opening framing first, so distant shots do not start with missing chunks.
        Pose first = cue.at(0, 0);
        applyPose(first, first.eye() != null);
        c.waitTicks(10);
        settle();
        owed = 0;
        int saved = 0;
        for (int i = 0; i < frames; i++) {
            double u = frames == 1 ? 0 : i / (double) (frames - 1);
            owed += ticksPerFrame;
            int ticks = (int) Math.floor(owed);
            owed -= ticks;
            if (frozenTime != null && i % 10 == 0) world.getServer().runCommand("time set " + frozenTime);
            if (ticks > 0) c.waitTicks(ticks);
            Pose pose = cue.at(i, u);
            applyPose(pose, pose.eye() != null);
            if (capture && i % step == 0) {
                c.takeScreenshot(TestScreenshotOptions.of(String.format("frame%05d", i))
                        .withDeltaTicks((float) owed).disableCounterPrefix().withDestinationDir(dir));
                saved++;
            }
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("name", name); entry.addProperty("frames", frames); entry.addProperty("saved", saved);
        entry.addProperty("fps", FPS); entry.addProperty("ticksPerFrame", ticksPerFrame); entry.addProperty("step", step);
        clips.add(entry);
        try { Files.writeString(out.resolve(manifest()), clips.toString()); } catch (Exception e) { throw new AssertionError(e); }
        System.out.println("TRAILER: " + name + " " + frames + " frames");
    }

    /** Sets the camera directly on the client so frames do not interpolate between server teleports. */
    private void applyPose(Pose pose, boolean move) {
        c.runOnClient(mc -> {
            var p = mc.player;
            if (move) p.snapTo(pose.eye().x, pose.eye().y - p.getEyeHeight(), pose.eye().z, pose.yaw(), pose.pitch());
            else { p.setYRot(pose.yaw()); p.setXRot(pose.pitch()); p.setYHeadRot(pose.yaw()); }
            p.setOldPosAndRot();
            p.setDeltaMovement(Vec3.ZERO);
        });
    }

    /**
     * Makes the test window itself 1920×1080, so the HUD and panel lay out at that size. A sized screenshot would
     * only enlarge the framebuffer and draw the 854×480 GUI layout in its top-left corner.
     */
    private void size() {
        c.runOnClient(mc -> {
            if (mc.getWindow().getWidth() != W || mc.getWindow().getHeight() != H)
                ((net.fabricmc.fabric.impl.client.gametest.util.WindowHooks) (Object) mc.getWindow()).fabric_resize(W, H);
        });
        c.waitTicks(2);
    }

    /** Waits for chunk meshes after teleports and terrain edits; Sodium can take a while after a big flatten. */
    private void settle() {
        try { world.getConnection().waitForChunksRender(false, 2400); }
        catch (AssertionError e) { System.out.println("TRAILER: chunks still rendering; continuing"); }
    }

    private void teleport(Vec3 feet, float yaw, float pitch) {
        world.getServer().runCommand("tp " + player + " " + feet.x + " " + feet.y + " " + feet.z + " " + yaw + " " + pitch);
        c.waitTicks(3);
    }

    private void dump(BlockPos min, BlockPos max, String name) throws Exception {
        Path file = out.getParent().resolve("blocks").resolve(name + ".json");
        Files.createDirectories(file.getParent());
        Map<String, Integer> palette = new LinkedHashMap<>();
        List<int[]> blocks = world.getServer().computeOnServer(server -> {
            List<int[]> found = new ArrayList<>();
            var level = server.overworld();
            for (BlockPos p : BlockPos.betweenClosed(min, max)) {
                BlockState state = level.getBlockState(p);
                if (state.isAir()) continue;
                int index = palette.computeIfAbsent(BlockStateParser.serialize(state), k -> palette.size());
                found.add(new int[]{p.getX() - min.getX(), p.getY() - min.getY(), p.getZ() - min.getZ(), index});
            }
            return found;
        });
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8); JsonWriter json = new JsonWriter(w)) {
            json.beginObject();
            json.name("origin").beginArray().value(min.getX()).value(min.getY()).value(min.getZ()).endArray();
            json.name("size").beginArray().value(max.getX() - min.getX() + 1).value(max.getY() - min.getY() + 1).value(max.getZ() - min.getZ() + 1).endArray();
            json.name("palette").beginArray(); for (String s : palette.keySet()) json.value(s); json.endArray();
            json.name("blocks").beginArray();
            for (int[] b : blocks) json.beginArray().value(b[0]).value(b[1]).value(b[2]).value(b[3]).endArray();
            json.endArray().endObject();
        }
        System.out.println("TRAILER: dumped " + blocks.size() + " blocks to " + file);
    }

    private void configure(int blocksPerTick) {
        try {
            var store = new ConfigStore(configFile);
            var config = store.load();
            config.maxSize = 128; config.blocksPerTick = blocksPerTick; config.confirmUndo = true;
            store.save(config);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private String plan(String name) {
        try { return Files.readString(plans.resolve(name + ".json")); } catch (Exception e) { throw new AssertionError(e); }
    }

    private void command(String command) {
        c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + command)); c.waitTicks(8);
        c.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
    }

    private static EditBox field(net.minecraft.client.Minecraft mc, String label) {
        return (EditBox) mc.gui.screen().children().stream().filter(w -> w instanceof EditBox e && e.getMessage().getString().equals(label))
                .findFirst().orElseThrow(() -> new AssertionError("No field " + label));
    }

    static String pos(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
    static double smooth(double u) { u = Math.max(0, Math.min(1, u)); return u * u * (3 - 2 * u); }

    static Pose look(Vec3 eye, Vec3 target) {
        Vec3 d = target.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        return new Pose(eye, yaw, pitch);
    }

    static Pose dolly(BlockPos fromEye, BlockPos toEye, BlockPos fromTarget, BlockPos toTarget, double u) {
        double s = smooth(u);
        return look(Vec3.atCenterOf(fromEye).lerp(Vec3.atCenterOf(toEye), s), Vec3.atCenterOf(fromTarget).lerp(Vec3.atCenterOf(toTarget), s));
    }

    /** A camera circling {@code center} at {@code radius}, between two bearings in degrees (0 = south of it). */
    static Pose orbit(Vec3 center, double radius, double rise, double fromDeg, double toDeg, double u) {
        double a = Math.toRadians(fromDeg + (toDeg - fromDeg) * smooth(u));
        Vec3 eye = center.add(Math.sin(a) * radius, rise, Math.cos(a) * radius);
        return look(eye, center);
    }
}
