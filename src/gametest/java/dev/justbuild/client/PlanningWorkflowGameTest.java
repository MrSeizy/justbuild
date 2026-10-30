package dev.justbuild.client;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.config.*;
import dev.justbuild.plan.*;
import dev.justbuild.world.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/** Real server-thread inspection/review loop, seeded preview, saved recipes, manual edits, and undo. */
public final class PlanningWorkflowGameTest implements FabricClientGameTest {
    private final List<JsonObject> prompts = new CopyOnWriteArrayList<>();
    private volatile List<String> editReplies = List.of();
    private static final String SHOULD_FIX = "{\"kind\":\"critique\",\"verdict\":\"revise\",\"problems\":[{\"severity\":\"should_fix\","
            + "\"category\":\"interior\",\"operations\":[0],\"cell\":null,\"problem\":\"The table needs room.\","
            + "\"fix\":\"Clear the cell in front of the door.\"}],\"keep\":[\"the paving\"]}";
    private static final String NOTHING = "{\"kind\":\"critique\",\"verdict\":\"ok\",\"problems\":[],\"keep\":[]}";

    private static BuildPlan paving(int y) {
        return new BuildPlan("Mixed paving", "Minimal paving", List.of(Operation.fill("minecraft:stone",
                new Cell(-3, y, -3), new Cell(3, y, 3), false).withMix("minecraft:cobblestone")));
    }

    @Override public void runTest(ClientGameTestContext context) {
        var file = FabricLoader.getInstance().getConfigDir().resolve("justbuild.json");
        byte[] original;
        try { original = Files.exists(file) ? Files.readAllBytes(file) : null; }
        catch (Exception e) { throw new AssertionError(e); }
        HttpServer stub = stub();
        try {
            var config = JustBuildConfig.defaults();
            var profile = new JustBuildConfig.ModelProfile("Planning test", "openai", "stub");
            profile.baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1";
            // The separate reviewer runs only with a reviewer profile chosen; this one answers from the same stub.
            var reviewer = new JustBuildConfig.ModelProfile("Planning reviewer", "openai", "stub");
            reviewer.baseUrl = profile.baseUrl;
            config.replaceProfiles(List.of(profile, reviewer), profile.name); config.detail = "quick"; config.previewAbove = 0;
            config.creativeDirection = false; config.maxQuestions = 0; new ConfigStore(file).save(config);
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksRender();
                BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition().above(12));
                String player = context.computeOnClient(mc -> mc.player.getName().getString());
                world.getServer().runOnServer(server -> {
                    for (BlockPos p : BlockPos.betweenClosed(at.offset(-12, -1, -12), at.offset(12, 8, 12)))
                        server.overworld().setBlock(p, p.getY() == at.getY() - 1 ? Blocks.STONE.defaultBlockState()
                                : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                });
                world.getServer().runCommand("gamemode creative " + player);
                world.getServer().runCommand("tp " + player + " " + (at.getX() + .5) + " " + at.getY() + " " + (at.getZ() + 9.5) + " 180 10");
                context.waitTicks(12);
                command(context, "zone 1 " + pos(at.offset(-4, -1, -4)));
                command(context, "zone 2 " + pos(at.offset(4, -1, 4)));
                command(context, "build a plain mixed stone paving pad");
                context.waitFor(mc -> ClientState.preview() != null, 1200);
                check(prompts.size() == 6, "Expected four reads, draft, and one corrective review, got " + prompts.size());
                check(words(2).contains("Request a smaller region"), "Oversized exact read did not give recoverable feedback");
                check(words(3).contains("Accepted properties") && words(3).contains("spacing=1"), "Earlier catalog/region reads were lost");
                check(words(4).contains("SUPPORT") && words(4).contains("reviewing your own plan"), "Quick mode missed diagnostic repair");
                check(words(5).contains("Inspection target: CANDIDATE"), "Review did not receive exact candidate cells");
                check(prompts.get(4).getAsJsonArray("messages").get(1).getAsJsonObject().getAsJsonArray("content").size() == 3,
                        "Review did not receive source and candidate images");
                check(state(world, at).isAir(), "Draft was placed before confirmation");

                context.runOnClient(mc -> mc.gui.setScreen(new WorkshopScreen(new JustBuildScreen())));
                context.clickScreenButton("Materials");
                context.runOnClient(mc -> ((net.minecraft.client.gui.components.EditBox) mc.gui.screen().children().stream()
                        .filter(w -> w instanceof net.minecraft.client.gui.components.EditBox e && e.getMessage().getString().equals("Texture seed"))
                        .findFirst().orElseThrow()).setValue("17"));
                context.takeScreenshot("planning-texture-controls"); context.clickScreenButton("Preview reroll");
                context.waitFor(mc -> ClientState.preview() != null);
                command(context, "confirm");
                context.waitFor(mc -> ClientState.preview() == null && ClientState.string("selectedBuild", null) != null);
                context.waitTicks(25);
                String id = context.computeOnClient(mc -> ClientState.string("selectedBuild", null));
                BuildRecord saved = record(world, id);
                check(saved.current().plan().texture().seed() == 17 && !PlanTextures.materials(saved.current().plan()).isEmpty(), "Preview lost seeded recipe");
                Map<BlockPos, BlockState> before = blocks(world, at);
                BlockPos manual = at;
                world.getServer().runOnServer(server -> server.overworld().setBlock(manual, Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS));
                command(context, "materials weight minecraft:cobblestone 0");
                context.waitFor(mc -> ClientState.preview() != null); command(context, "confirm");
                context.waitFor(mc -> ClientState.preview() == null); context.waitTicks(25);
                check(blocks(world, at).entrySet().stream().allMatch(e -> e.getValue().is(e.getKey().equals(manual) ? Blocks.DIAMOND_BLOCK : Blocks.STONE)),
                        "Weights changed geometry or overwrote a manual block");
                check(!PlanTextures.materials(record(world, id).current().plan()).isEmpty(), "Saved weight edit discarded the recipe");
                command(context, "undo"); command(context, "undo confirm"); context.waitTicks(30);
                before.put(manual, Blocks.DIAMOND_BLOCK.defaultBlockState());
                check(before.equals(blocks(world, at)), "Undo did not restore exact prior texture and manual edit");
                command(context, "materials reroll 18"); context.waitFor(mc -> ClientState.preview() != null);
                command(context, "preview discard");
                check(prompts.size() == 6, "A local material control called the AI");

                // A full edit draft must survive a destructive repair, including a second critique and repair round.
                Map<BlockPos, BlockState> pavingBefore = blocks(world, at);
                BuildPlan current = record(world, id).current().plan();
                var furnishedOps = new ArrayList<>(current.operations());
                furnishedOps.add(Operation.set("crafting_table", new Cell(0, 1, 0)));
                BuildPlan furnished = new BuildPlan(current.title(), "Added a workstation", furnishedOps, current.texture());
                BuildPlan destructive = new BuildPlan("Furniture only", "", List.of(Operation.set("crafting_table", new Cell(0, 1, 0))));
                var litOps = new ArrayList<>(furnishedOps);
                litOps.add(Operation.set("lantern", new Cell(0, 2, 0)));
                BuildPlan lit = new BuildPlan(current.title(), "Added a lantern", litOps, current.texture());
                // Edit draft, critique, destructive repair (rejected), critique, repair; then the lantern draft and a satisfied
                // critique; then, with Same as builder, an edit draft and the builder's own review of it.
                editReplies = List.of(PlanWriter.write(furnished), SHOULD_FIX, PlanWriter.write(destructive), SHOULD_FIX,
                        PlanWriter.write(furnished), PlanWriter.write(lit), NOTHING, PlanWriter.write(furnished),
                        PlanWriter.write(furnished));
                config.detail = "masterpiece"; config.reviewerProfile = reviewer.name; new ConfigStore(file).save(config);
                command(context, "reload");
                command(context, "edit add a crafting table and preserve the paving");
                context.waitFor(mc -> ClientState.preview() != null, 1200);
                check(prompts.size() == 11, "Expected an edit draft and two critique/repair rounds, got " + prompts.size());
                check(words(7).contains("Independent review (round 1 of 2)")
                        && words(7).contains("Original build request: a plain mixed stone paving pad"),
                        "The critique lost the original build context");
                check(prompts.get(7).getAsJsonArray("messages").get(1).getAsJsonObject().getAsJsonArray("content").size() == 4,
                        "The critique did not receive source, candidate and facade images");
                check(words(8).contains("Original build request: a plain mixed stone paving pad")
                        && words(8).contains("Requested change: add a crafting table and preserve the paving")
                        && words(8).contains("Clear the cell in front of the door."),
                        "The repair lost original build or current edit context, or the critique");
                check(words(9).contains("Independent review (round 2 of 2)") && words(9).contains("removed existing structure"),
                        "The second critique did not hear that the first repair was rejected");
                check(words(10).contains("Mixed paving") && words(10).contains("COMPLETE replacement plan"),
                        "The next repair did not retain the complete draft after rejecting demolition");
                command(context, "confirm"); context.waitFor(mc -> ClientState.preview() == null); context.waitTicks(25);
                check(pavingBefore.equals(blocks(world, at)), "A repair removed or changed paving retained by the edit draft");
                check(state(world, at.above()).is(Blocks.CRAFTING_TABLE), "The accepted draft lost its requested furniture");

                // A satisfied reviewer costs one short call: no repair.
                config.detail = "detailed"; new ConfigStore(file).save(config); command(context, "reload");
                command(context, "edit add a lantern on the table");
                context.waitFor(mc -> ClientState.preview() != null, 1200);
                check(prompts.size() == 13, "A satisfied critique still called a repair: " + prompts.size() + " calls");
                check(words(12).contains("Independent review (round 1 of 1)"), "One pass did not ask the reviewer");
                command(context, "confirm"); context.waitFor(mc -> ClientState.preview() == null); context.waitTicks(25);
                check(state(world, at.above(2)).is(Blocks.LANTERN), "The reviewed draft was not placed");
                command(context, "undo"); command(context, "undo confirm"); context.waitTicks(30);
                command(context, "undo"); command(context, "undo confirm"); context.waitTicks(30);
                check(state(world, at.above()).isAir() && pavingBefore.equals(blocks(world, at)), "Review fallback broke edit undo");

                // Same as builder keeps the builder's own review: One pass makes one review call and no critique.
                config.reviewerProfile = ""; new ConfigStore(file).save(config); command(context, "reload");
                command(context, "edit add a crafting table");
                context.waitFor(mc -> ClientState.preview() != null, 1200);
                check(prompts.size() == 15, "Expected an edit draft and one self-review, got " + prompts.size() + " calls");
                check(words(14).contains("You are reviewing your own plan before it is built (round 1 of 1)")
                        && !prompts.get(14).toString().contains("independent reviewer"),
                        "Same as builder did not ask the builder to review its own plan");
                command(context, "preview discard");
            }
        } catch (Exception e) { throw new AssertionError("Planning workflow failed", e); }
        finally {
            stub.stop(0);
            try { if (original == null) Files.deleteIfExists(file); else Files.write(file, original); }
            catch (Exception e) { throw new AssertionError("Could not restore config", e); }
        }
    }

    private String words(int index) {
        return prompts.get(index).getAsJsonArray("messages").get(1).getAsJsonObject().getAsJsonArray("content")
                .get(0).getAsJsonObject().get("text").getAsString();
    }
    private static BuildRecord record(TestSingleplayerContext world, String id) {
        return world.getServer().computeOnServer(server -> new BuildStore(server.getWorldPath(LevelResource.ROOT).resolve("justbuild/builds"))
                .loadAll().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow());
    }
    private static Map<BlockPos, BlockState> blocks(TestSingleplayerContext world, BlockPos at) {
        return world.getServer().computeOnServer(server -> {
            Map<BlockPos, BlockState> values = new LinkedHashMap<>();
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-3, 0, -3), at.offset(3, 0, 3))) values.put(p.immutable(), server.overworld().getBlockState(p));
            return values;
        });
    }
    private static BlockState state(TestSingleplayerContext world, BlockPos p) { return world.getServer().computeOnServer(s -> s.overworld().getBlockState(p)); }
    private static String pos(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
    private static void command(ClientGameTestContext context, String text) { context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + text)); context.waitTicks(8); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private HttpServer stub() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            List<String> replies = List.of(
                    "{\"kind\":\"blocks\",\"blockQuery\":{\"query\":\"minecraft:oak_stairs\",\"offset\":0}}",
                    "{\"kind\":\"inspect\",\"inspection\":{\"from\":[-20,-20,-20],\"to\":[20,20,20],\"mode\":\"exact\",\"target\":\"source\"}}",
                    "{\"kind\":\"inspect\",\"inspection\":{\"from\":[-3,-1,-3],\"to\":[3,0,3],\"mode\":\"exact\",\"target\":\"source\"}}",
                    PlanWriter.write(paving(2)),
                    "{\"kind\":\"inspect\",\"inspection\":{\"from\":[-3,0,-3],\"to\":[3,2,3],\"mode\":\"exact\",\"target\":\"candidate\"}}",
                    PlanWriter.write(paving(0)));
            server.createContext("/v1/chat/completions", exchange -> {
                int index = prompts.size(); prompts.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
                String response = index < replies.size() ? replies.get(index)
                        : index - replies.size() < editReplies.size() ? editReplies.get(index - replies.size())
                        : "{\"kind\":\"decline\",\"summary\":\"Unexpected call\"}";
                JsonObject message = new JsonObject(); message.addProperty("content", response);
                JsonObject choice = new JsonObject(); choice.add("message", message); JsonArray choices = new JsonArray(); choices.add(choice);
                JsonObject reply = new JsonObject(); reply.add("choices", choices); byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start(); return server;
        } catch (Exception e) { throw new AssertionError(e); }
    }
}
