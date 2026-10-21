package dev.justbuild.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.Operation;
import dev.justbuild.plan.PlanWriter;
import dev.justbuild.world.BuildRecord;
import dev.justbuild.world.BuildStore;
import dev.justbuild.world.ProjectStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.LevelResource;

/** Actual-world preservation, persisted local undo, shared project prompts, cutaways and accessible workshop screens. */
public final class WorkshopGameTest implements FabricClientGameTest {
    private final Queue<String> replies = new ConcurrentLinkedQueue<>(), prompts = new ConcurrentLinkedQueue<>();
    private final AtomicInteger calls = new AtomicInteger();

    @Override public void runTest(ClientGameTestContext context) {
        HttpServer stub = stub(); var config = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("justbuild.json")); configure(config, stub.getAddress().getPort());
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            BlockPos base = context.computeOnClient(mc -> mc.player.blockPosition().offset(4, 16, 10));
            String player = context.computeOnClient(mc -> mc.player.getName().getString());
            BlockPos missing = base.offset(0, 2, 3), manual = base.offset(6, 2, 3), stair = base.offset(2, 1, 2), chest = base.offset(4, 1, 4);
            world.getServer().runOnServer(server -> {
                for (BlockPos p : BlockPos.betweenClosed(base.offset(-2, -1, -2), base.offset(22, 8, 8))) {
                    int x = p.getX() - base.getX(), y = p.getY() - base.getY(), z = p.getZ() - base.getZ();
                    boolean shell = x >= 0 && x <= 6 && z >= 0 && z <= 6 && y >= 0 && y <= 5
                            && (x == 0 || x == 6 || y == 0 || y == 5 || z == 0 || z == 6);
                    server.overworld().setBlock(p, shell ? Blocks.OAK_PLANKS.defaultBlockState() : y == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
                var door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
                server.overworld().setBlock(base.offset(3, 1, 0), door, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                server.overworld().setBlock(base.offset(3, 2, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                server.overworld().setBlock(stair, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                server.overworld().setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            });
            camera(context, world, player, base);
            command(context, "box 1 " + pos(base)); command(context, "box 2 " + pos(base.offset(6, 5, 6)));
            context.runOnClient(mc -> mc.gui.setScreen(new WorkshopScreen(new JustBuildScreen())));
            context.getInput().pressKey(InputConstants.KEY_A); context.waitForScreen(AddExistingScreen.class);
            text(context, "Building name", "Handbuilt House"); context.takeScreenshot("workshop-adopt-controls"); context.clickScreenButton("Add build and save original");
            context.waitFor(mc -> ClientState.string("selectedTitle", "").equals("Handbuilt House"));
            String id = context.computeOnClient(mc -> ClientState.string("selectedBuild", null));
            context.waitFor(mc -> !ClientState.versions().isEmpty() && !ClientState.versions().getFirst().thumbnail().colors().isEmpty(), 400);
            BuildRecord adopted = record(world, id);
            check(adopted.adopted() && adopted.versions().size() == 1 && adopted.checkpoints().size() == 1, "adoption did not save the original record and checkpoint");
            check(!adopted.baseline().containsKey(chest), "adoption acquired a container position");
            check(state(world, chest).is(Blocks.CHEST) && state(world, missing).is(Blocks.OAK_PLANKS), "adoption changed the building");
            close(context); command(context, "undo"); command(context, "undo confirm");
            check(state(world, missing).is(Blocks.OAK_PLANKS) && record(world, id).versions().size() == 1, "undo removed an adopted original");

            context.runOnClient(mc -> {
                ClientState.update("{\"workshop\":{\"palette\":{\"not an id!\":1,\"missing:block\":2}}}");
                mc.gui.setScreen(new MaterialPickerScreen(new JustBuildScreen(), true, ignored -> { }));
            });
            close(context); command(context, "materials"); context.runOnClient(mc -> mc.gui.setScreen(new MaterialsScreen(new JustBuildScreen())));
            context.clickScreenButton("Choose from building"); context.waitForScreen(MaterialPickerScreen.class); context.takeScreenshot("workshop-building-palette");
            context.clickScreenButton("   Oak Stairs × 1"); context.clickScreenButton("Choose replacement"); text(context, "Search blocks", "stairs"); context.takeScreenshot("workshop-block-picker");
            text(context, "Search blocks", "stone brick stairs"); context.clickScreenButton("   Stone Brick Stairs");
            context.clickScreenButton("Preview replacement"); awaitPreview(context);
            check(state(world, stair).is(Blocks.OAK_STAIRS), "material swap skipped preview");
            context.runOnClient(mc -> mc.gui.setScreen(new JustBuildScreen())); context.tryClickScreenButton("Create");
            context.clickScreenButton("Floors");
            context.waitForScreen(FloorPreviewScreen.class); context.waitFor(mc -> !FloorPreview.blocks().isEmpty());
            context.clickScreenButton("Lower layer"); context.clickScreenButton("Lower layer"); context.clickScreenButton("Lower layer");
            check(context.computeOnClient(mc -> FloorPreview.ceiling() == base.getY() + 2), "cutaway controls did not lower the ceiling");
            check(state(world, base.offset(2, 5, 2)).is(Blocks.OAK_PLANKS), "cutaway removed the real roof");
            context.takeScreenshot("workshop-floor-cutaway"); close(context); command(context, "confirm"); awaitBlock(context, world, stair, Blocks.STONE_BRICK_STAIRS);
            check(context.computeOnClient(mc -> FloorPreview.blocks().isEmpty()), "confirm did not clear the section model");
            check(state(world, stair).getValue(StairBlock.FACING) == Direction.EAST, "material swap lost stair facing");
            check(!record(world, id).current().undoStates().isEmpty(), "local edit did not persist its actual previous blocks");
            undo(context); awaitBlock(context, world, stair, Blocks.OAK_STAIRS);
            checkZonedLocalEdits(context, world, id, base, missing, manual);

            world.getServer().runOnServer(server -> {
                server.overworld().setBlock(missing, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                server.overworld().setBlock(manual, Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            });
            command(context, "box 1 " + pos(missing)); command(context, "repair all");
            check(context.computeOnClient(mc -> ClientState.preview() == null), "incomplete part selection allowed a whole-building repair");
            command(context, "box clear");
            context.runOnClient(mc -> mc.gui.setScreen(new RepairScreen(new JustBuildScreen()))); context.takeScreenshot("workshop-repair-controls"); context.clickScreenButton("Preview repair");
            awaitPreview(context); command(context, "confirm"); awaitBlock(context, world, missing, Blocks.OAK_PLANKS);
            check(state(world, manual).is(Blocks.DIAMOND_BLOCK), "missing-only repair changed a manual replacement");
            undo(context); awaitBlock(context, world, missing, Blocks.AIR); check(state(world, manual).is(Blocks.DIAMOND_BLOCK), "undo lost manual material");
            command(context, "repair all Original building"); awaitPreview(context); command(context, "confirm"); awaitBlock(context, world, manual, Blocks.OAK_PLANKS);
            undo(context); awaitBlock(context, world, manual, Blocks.DIAMOND_BLOCK); check(state(world, missing).isAir(), "repair undo did not restore the damage");
            command(context, "box 1 " + pos(manual)); command(context, "box 2 " + pos(manual)); command(context, "lock Manual wall");
            command(context, "repair all"); awaitPreview(context); command(context, "confirm"); awaitBlock(context, world, missing, Blocks.OAK_PLANKS);
            check(state(world, manual).is(Blocks.DIAMOND_BLOCK) && state(world, chest).is(Blocks.CHEST), "repair bypassed protected positions");

            context.runOnClient(mc -> mc.gui.setScreen(new ProjectsScreen(new JustBuildScreen()))); text(context, "Project name", "Riverside"); context.clickScreenButton("Create");
            context.waitFor(mc -> ClientState.projects().size() == 1); String projectId = context.computeOnClient(mc -> ClientState.activeProject());
            command(context, "project add"); command(context, "project style medieval"); command(context, "project palette oak_planks,stone_bricks");
            context.runOnClient(mc -> mc.gui.setScreen(new ProjectDetailsScreen(new JustBuildScreen(), projectId))); context.takeScreenshot("workshop-project-controls");
            // Each project screen, opened from its button (so GlassGuard checks them too).
            context.clickScreenButton("Buildings"); context.waitForScreen(ProjectMembersScreen.class); context.takeScreenshot("workshop-project-buildings");
            check(active(context, "Handbuilt House · v"), "project buildings do not list the adopted build");
            context.clickScreenButton("Back"); context.clickScreenButton("Team"); context.waitForScreen(ProjectTeamScreen.class); context.takeScreenshot("workshop-project-team");
            check(!active(context, "Invite"), "Invite is pressable before a name is typed");
            context.clickScreenButton("Back"); context.clickScreenButton("Shared palette…"); context.waitForScreen(ProjectPaletteScreen.class);
            context.takeScreenshot("workshop-project-palette");
            check(active(context, "Oak Planks ✕") && active(context, "Stone Bricks ✕") && active(context, "Clear"), "the shared palette does not show its blocks");
            context.clickScreenButton("Back"); context.waitForScreen(ProjectDetailsScreen.class);
            check(projects(world).getFirst().builds().contains(id), "project membership was not saved");
            close(context); command(context, "reload"); check(projects(world).getFirst().theme().palette().size() == 2, "project palette did not survive reload");

            replies.add(PlanWriter.write(new BuildPlan("Interior", "", List.of(Operation.set("emerald_block", new Cell(2, 1, 3)),
                    Operation.set("gold_block", new Cell(0, 2, 2)), Operation.set("chest", new Cell(4, 1, 4))))));
            RelaySeen.clear(); context.runOnClient(mc -> mc.gui.setScreen(new InteriorScreen(new JustBuildScreen()))); context.takeScreenshot("workshop-interior-controls");
            context.clickScreenButton("Preview interior"); awaitCalls(context, 1); awaitPreview(context);
            check(prompts.peek().contains("Shared village project") && prompts.peek().contains("Focused edit"), "interior omitted shared theme or shell boundary");
            check(RelaySeen.snapshot().requests().contains(dev.justbuild.prompt.InteriorRequests.request("home", "complete", "")), "relay did not recognize the chosen interior template");
            command(context, "confirm"); awaitBlock(context, world, base.offset(2, 1, 3), Blocks.EMERALD_BLOCK);
            check(state(world, base.offset(0, 2, 2)).is(Blocks.OAK_PLANKS), "interior planner changed the shell");
            check(state(world, manual).is(Blocks.DIAMOND_BLOCK) && state(world, chest).is(Blocks.CHEST), "interior planner changed protected blocks");
            check(!record(world, id).baseline().containsKey(chest), "a blocked AI placement acquired an original container");
            command(context, "materials replace minecraft:chest minecraft:trapped_chest");
            check(context.computeOnClient(mc -> ClientState.preview() == null), "a planned but unplaced chest became owned on the next edit");
            check(state(world, chest).is(Blocks.CHEST), "original container was not preserved across versions");
            check(context.computeOnClient(mc -> ClientState.part() == null), "interior work left a permanent part restriction");
            command(context, "walkability"); context.waitFor(mc -> ClientState.route() != null);
            context.runOnClient(mc -> mc.gui.setScreen(new WalkabilityScreen(new JustBuildScreen()))); context.takeScreenshot("workshop-walkability-controls");
            close(context); check(calls.get() == 1, "a local workshop tool used AI");

            BlockPos marker = base.east(16);
            command(context, "select clear"); command(context, "zone 1 " + pos(marker.below())); command(context, "zone 2 " + pos(marker.below()));
            replies.add(PlanWriter.write(new BuildPlan("Village marker", "", List.of(Operation.set("gold_block", new Cell(0, 0, 0))))));
            command(context, "build a village meeting marker"); awaitCalls(context, 2); awaitPreview(context);
            check(prompts.stream().allMatch(p -> p.contains("Shared village project")), "new project building omitted its shared theme");
            command(context, "confirm"); awaitBlock(context, world, marker, Blocks.GOLD_BLOCK);
            check(projects(world).getFirst().builds().size() == 2, "new building did not join the active project");
            undo(context); awaitBlock(context, world, marker, Blocks.AIR); check(state(world, missing).is(Blocks.OAK_PLANKS), "project undo changed another building");
            check(projects(world).getFirst().builds().size() == 1, "undo left a deleted building in the project");
            check(calls.get() == 2, "project grouping or undo used AI");
            replies.add(PlanWriter.write(new BuildPlan("Fitting option", "", List.of(Operation.set("lapis_block", new Cell(0, 0, 0))))));
            replies.add(PlanWriter.write(new BuildPlan("Outside option", "", List.of(Operation.set("emerald_block", new Cell(2, 0, 0))))));
            command(context, "alternatives build 2 a village marker"); awaitCalls(context, 4); awaitPreview(context);
            check(context.computeOnClient(mc -> ClientState.preview().options().size() == 1 && ClientState.string("retainedPlan", null) == null),
                    "an out-of-zone alternative incorrectly promised retained retry");
            check(context.computeOnClient(mc -> ClientState.lines().stream().noneMatch(line -> line.getString().contains("Plan kept for 30 minutes"))),
                    "the alternative failure advertised a discarded plan");
            command(context, "cancel");
        } finally {
            stub.stop(0); try { config.save(JustBuildConfig.defaults()); } catch (Exception e) { throw new AssertionError(e); }
        }
    }
    private static void checkZonedLocalEdits(ClientGameTestContext context, TestSingleplayerContext world,
            String id, BlockPos base, BlockPos inside, BlockPos outside) {
        // A saved build can span older zones. A material swap must edit only the current footprint.
        command(context, "zone 1 " + pos(inside.below())); command(context, "zone 2 " + pos(inside.below()));
        command(context, "materials replace oak_planks stone_bricks");
        context.waitFor(mc -> ClientState.preview() != null, 100);
        check(state(world, inside).is(Blocks.OAK_PLANKS), "zoned material swap skipped preview");
        command(context, "confirm"); awaitBlock(context, world, inside, Blocks.STONE_BRICKS);
        check(state(world, outside).is(Blocks.OAK_PLANKS), "material swap changed a matching block outside the zone");
        var changed = record(world, id).current().undoStates();
        check(changed.size() == 6 && changed.keySet().stream().allMatch(p -> p.getX() == inside.getX() && p.getZ() == inside.getZ()),
                "zoned material swap did not preserve the exact footprint and full height");
        command(context, "repair all");
        check(context.computeOnClient(mc -> ClientState.preview() == null), "local edit lost the saved state of untouched blocks");
        undo(context); awaitBlock(context, world, inside, Blocks.OAK_PLANKS);

        command(context, "box 1 " + pos(inside)); command(context, "box 2 " + pos(inside));
        command(context, "materials replace oak_planks stone_bricks"); awaitPreview(context);
        command(context, "confirm"); awaitBlock(context, world, inside, Blocks.STONE_BRICKS);
        check(record(world, id).current().undoStates().size() == 1 && state(world, inside.above()).is(Blocks.OAK_PLANKS),
                "material swap ignored the part selection inside the zone");
        undo(context); awaitBlock(context, world, inside, Blocks.OAK_PLANKS);
        command(context, "lock Keep wall");
        String lock = context.computeOnClient(mc -> ClientState.locks().getFirst().id());
        command(context, "materials replace oak_planks stone_bricks"); awaitPreview(context);
        command(context, "confirm"); awaitBlock(context, world, inside.above(), Blocks.STONE_BRICKS);
        check(state(world, inside).is(Blocks.OAK_PLANKS) && state(world, outside).is(Blocks.OAK_PLANKS),
                "zoned material swap ignored a lock or the zone");
        undo(context); awaitBlock(context, world, inside.above(), Blocks.OAK_PLANKS);
        command(context, "unlock " + lock);

        command(context, "materials replace oak_stairs stone_brick_stairs");
        check(context.computeOnClient(mc -> ClientState.preview() == null
                && ClientState.lines().getLast().getString().contains("No matching changes")),
                "matches outside the zone should report no editable matches");
        world.getServer().runOnServer(server -> {
            server.overworld().setBlock(inside, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            server.overworld().setBlock(outside, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        });
        command(context, "repair missing"); awaitPreview(context);
        command(context, "confirm"); awaitBlock(context, world, inside, Blocks.OAK_PLANKS);
        check(state(world, outside).isAir(), "local repair changed a block outside the zone");
        undo(context); awaitBlock(context, world, inside, Blocks.AIR);
        check(state(world, outside).isAir(), "zoned repair undo changed a block outside the zone");
        world.getServer().runOnServer(server -> {
            server.overworld().setBlock(inside, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            server.overworld().setBlock(outside, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        });
        command(context, "zone clear");
        command(context, "materials replace oak_stairs stone_brick_stairs"); awaitPreview(context); command(context, "cancel");
        command(context, "zone 1 " + pos(base)); command(context, "zone 2 " + pos(base.offset(6, 5, 6)));
    }

    private static BuildRecord record(TestSingleplayerContext world, String id) {
        return world.getServer().computeOnServer(server -> new BuildStore(server.getWorldPath(LevelResource.ROOT).resolve("justbuild/builds")).loadAll()
                .stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow());
    }
    private static List<ProjectStore.Project> projects(TestSingleplayerContext world) {
        return world.getServer().computeOnServer(server -> {
            try { return new ProjectStore(server.getWorldPath(LevelResource.ROOT).resolve("justbuild/projects.json")).load(); }
            catch (java.io.IOException e) { throw new AssertionError(e); }
        });
    }
    private static void camera(ClientGameTestContext context, TestSingleplayerContext world, String name, BlockPos at) {
        world.getServer().runCommand("gamemode spectator " + name);
        world.getServer().runCommand("tp " + name + " " + (at.getX() + 3.5) + " " + (at.getY() + 8) + " " + (at.getZ() - 8.5) + " 0 26"); context.waitTicks(10);
    }
    private static String pos(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
    private static void command(ClientGameTestContext context, String text) { context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + text)); context.waitTicks(8); }
    private static void undo(ClientGameTestContext context) { command(context, "undo"); command(context, "undo confirm"); }
    private static void close(ClientGameTestContext context) { context.runOnClient(mc -> mc.gui.setScreen(null)); }
    private static void text(ClientGameTestContext context, String label, String value) {
        context.runOnClient(mc -> ((EditBox) mc.gui.screen().children().stream().filter(w -> w instanceof EditBox e && e.getMessage().getString().equals(label)).findFirst().orElseThrow()).setValue(value));
    }
    /** Whether the button whose label starts with {@code prefix} can be pressed; fails when there is none. */
    private static boolean active(ClientGameTestContext context, String prefix) {
        return context.computeOnClient(mc -> mc.gui.screen().children().stream()
                .filter(w -> w instanceof Button b && b.getMessage().getString().startsWith(prefix)).map(w -> ((Button) w).active)
                .findFirst().orElseThrow(() -> new AssertionError("No button labelled " + prefix + " on " + mc.gui.screen().getClass().getSimpleName())));
    }
    private static BlockState state(TestSingleplayerContext world, BlockPos p) { return world.getServer().computeOnServer(server -> server.overworld().getBlockState(p)); }
    private static void awaitBlock(ClientGameTestContext context, TestSingleplayerContext world, BlockPos p, Block block) {
        for (int i = 0; i < 100 && !state(world, p).is(block); i++) context.waitTicks(5);
        check(state(world, p).is(block), "expected " + block + " at " + p + ", got " + state(world, p)); context.waitTicks(10);
    }
    private static void awaitPreview(ClientGameTestContext context) { context.waitFor(mc -> ClientState.preview() != null); }
    private void awaitCalls(ClientGameTestContext context, int count) {
        for (int i = 0; i < 150 && calls.get() < count; i++) context.waitTicks(5);
        check(calls.get() == count, "expected " + count + " AI calls, got " + calls.get()); context.waitTicks(12);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private HttpServer stub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), review = StubReview.draft(body);
                if (review == null) { prompts.add(body); calls.incrementAndGet(); }
                String response = review != null ? review : replies.poll(); JsonObject message = new JsonObject(); message.addProperty("content", response == null ? "{\"kind\":\"decline\",\"summary\":\"Unexpected call\"}" : response);
                JsonObject choice = new JsonObject(); choice.add("message", message); JsonArray choices = new JsonArray(); choices.add(choice);
                JsonObject reply = new JsonObject(); reply.add("choices", choices); byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start(); return server;
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static void configure(ConfigStore store, int port) {
        try {
            var config = JustBuildConfig.defaults(); var stub = new JustBuildConfig.ModelProfile("Stub", "openai", "stub"); stub.baseUrl = "http://127.0.0.1:" + port + "/v1";
            config.replaceProfiles(List.of(stub), "Stub"); config.previewAbove = 0; config.detail = "quick"; config.creativeDirection = false; config.maxQuestions = 0; store.save(config);
        } catch (Exception e) { throw new AssertionError(e); }
    }
}
