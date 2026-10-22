package dev.justbuild.client;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.JustBuildMod;
import dev.justbuild.config.*;
import dev.justbuild.plan.*;
import dev.justbuild.session.PreviewStore;
import dev.justbuild.world.BuildStore;
import dev.justbuild.world.BuildRecord;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Real disconnect/reconnect and world restarts, with provider calls counted independently of restored state. */
public final class ResumePreviewGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext c) {
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve("justbuild.json");
        Path draftsFile = configFile.resolveSibling("justbuild-drafts.json");
        byte[] oldConfig = read(configFile), oldDrafts = read(draftsFile);
        AtomicInteger calls = new AtomicInteger();
        var release = new java.util.concurrent.CountDownLatch(1);
        HttpServer stub = stub(calls, release);
        try {
            var config = JustBuildConfig.defaults(); var profile = new JustBuildConfig.ModelProfile("Stub", "openai", "stub");
            profile.baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1";
            config.replaceProfiles(List.of(profile), "Stub"); config.detail = "quick"; config.creativeDirection = false;
            config.playerAi = "off"; config.previewAbove = 0;
            var configStore = new ConfigStore(configFile); configStore.save(config);
            TestWorldSave save; String actor; String draftKey; PreviewStore.Saved saved;
            String text = "An unsent cabin request\nKeep the café entrance clear 🍂";
            try (var world = c.worldBuilder().create()) {
                save = world.getWorldSave(); world.getConnection().waitForChunksRender();
                actor = c.computeOnClient(mc -> mc.player.getUUID().toString());
                BlockPos at = c.computeOnClient(mc -> mc.player.blockPosition().above(12));
                world.getServer().runOnServer(server -> {
                    var m = JustBuildMod.manager(); var source = server.getPlayerList().getPlayers().getFirst().createCommandSourceStack();
                    for (BlockPos p : BlockPos.betweenClosed(at.offset(-10, -1, -10), at.offset(10, 4, 10)))
                        server.overworld().setBlock(p, p.getY() == at.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    m.zoneCorner(source, 1, at.offset(-8, -1, -8)); m.zoneCorner(source, 2, at.offset(8, -1, 8));
                    m.autoBuild(source, "on"); m.startAlternatives(source, false, 3, "Three different markers");
                });
                c.waitFor(mc -> ClientState.preview() != null && ClientState.preview().options().size() == 3, 1200);
                command(c, "preview option 2");
                world.getServer().runOnServer(server -> JustBuildMod.manager().adjustPreview(
                        server.getPlayerList().getPlayers().getFirst().createCommandSourceStack(), 3, 0, 0, 90, false));
                c.waitTicks(10);
                saved = new PreviewStore(save.getSaveDirectory().resolve("justbuild/previews")).load(actor);
                check(saved != null && saved.choices().size() == 3 && saved.selected() == 1, "the selected alternative wasn't saved");
                check(saved.choices().get(1).typedRequest() != null && saved.choices().get(1).typedRequest().contains("Three different markers"),
                        "moving and saving a preview lost the original typed request");
                draftKey = c.computeOnClient(mc -> PromptDrafts.key(null, null));
                c.runOnClient(mc -> {
                    mc.gui.setScreen(new PromptEditorScreen(new JustBuildScreen(), null, null, 1, () -> {}));
                    mc.gui.screen().children().stream().filter(MultiLineEditBox.class::isInstance).map(MultiLineEditBox.class::cast)
                            .findFirst().orElseThrow().setValue(text);
                    mc.gui.setScreen(null);
                });
                check(text.equals(new PromptDraftStore(draftsFile).load().get(draftKey)), "typed draft did not reach disk");
            }
            int generated = calls.get(); check(generated == 3, "alternatives used an unexpected number of AI calls");
            PreviewStore previewStore = new PreviewStore(save.getSaveDirectory().resolve("justbuild/previews"));
            check(saved.equals(previewStore.load(actor)), "shutdown changed the saved alternatives");
            BlockPos center = saved.choices().get(1).origin();
            List<BlockPos> chosen = positions(saved.choices().get(1));
            new dev.justbuild.world.RegionStore(save.getSaveDirectory().resolve("justbuild/locked-areas.json")).save(List.of(
                    new dev.justbuild.world.RegionStore.Locked("aabbccdd", actor, "Added while away",
                            dev.justbuild.world.BuildRegion.between(net.minecraft.world.level.Level.OVERWORLD, chosen.getFirst(), chosen.getFirst()))));
            String buildId;
            try (var world = save.open()) {
                world.getConnection().waitForChunksRender();
                c.waitFor(mc -> ClientState.string("retainedPlan", null) != null);
                check(c.computeOnClient(mc -> ClientState.preview() == null), "reopening automatically resumed a preview");
                check(c.computeOnClient(mc -> PromptDrafts.get(PromptDrafts.key(null, null)).equals(text)), "world reopening lost its draft");
                world.getServer().runOnServer(server -> {
                    check(chosen.stream().allMatch(p -> server.overworld().getBlockState(p).isAir()), "restart placed the preview");
                    server.overworld().setBlock(center, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
                    ((ChestBlockEntity) server.overworld().getBlockEntity(center)).setItem(0, new ItemStack(Items.DIAMOND, 5));
                });
                int limit = config.maxBlocks; config.maxBlocks = 2; configStore.save(config);
                command(c, "reload"); command(c, "retry");
                check(c.computeOnClient(mc -> ClientState.preview() == null), "resume ignored current limits");
                check(previewStore.load(actor).equals(saved), "failed resume consumed the saved options");
                config.maxBlocks = limit; configStore.save(config); command(c, "reload"); command(c, "retry");
                c.waitFor(mc -> ClientState.preview() != null && ClientState.preview().options().size() == 3);
                check(c.computeOnClient(mc -> ClientState.preview().option() == 1), "resume lost the selected option");
                check(previewStore.load(actor).choices().get(1).origin().equals(center), "resume moved the saved frame");
                c.runOnClient(mc -> mc.gui.setScreen(new JustBuildScreen())); c.waitTicks(3);
                c.takeScreenshot("resumed-alternatives"); c.runOnClient(mc -> mc.gui.setScreen(null));
                command(c, "preview option 3"); command(c, "preview option 2"); command(c, "confirm");
                c.waitFor(mc -> ClientState.preview() == null && !ClientState.builds().isEmpty()); c.waitTicks(20);
                buildId = c.computeOnClient(mc -> ClientState.builds().getFirst().id());
                check(previewStore.load(actor) == null, "confirmation left a replayable saved preview");
                world.getServer().runOnServer(server -> {
                    check(server.overworld().getBlockState(chosen.getFirst()).isAir(), "resume ignored a lock added while away");
                    check(server.overworld().getBlockState(chosen.getLast()).is(Blocks.DIAMOND_BLOCK), "wrong alternative was placed");
                    check(server.overworld().getBlockEntity(center) instanceof ChestBlockEntity chest && chest.getItem(0).getCount() == 5,
                            "resume or confirmation destroyed new belongings");
                    // A repair can change blocks even when its full saved plan equals the current version's plan.
                    server.overworld().setBlock(chosen.getLast(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    var source = server.getPlayerList().getPlayers().getFirst().createCommandSourceStack();
                    JustBuildMod.manager().repair(source, true, null);
                });
                c.waitFor(mc -> ClientState.preview() != null);
            }
            try (var world = save.open()) {
                world.getConnection().waitForChunksRender(); c.waitFor(mc -> ClientState.string("retainedPlan", null) != null);
                command(c, "retry"); c.waitFor(mc -> ClientState.preview() != null); command(c, "confirm"); c.waitTicks(20);
                world.getServer().runOnServer(server -> check(server.overworld().getBlockState(chosen.getLast()).is(Blocks.DIAMOND_BLOCK), "a resumed repair lost its local changes"));
                command(c, "undo"); command(c, "undo confirm"); c.waitTicks(20);
                world.getServer().runOnServer(server -> {
                    check(server.overworld().getBlockState(chosen.getLast()).isAir(), "resumed repair undo did not restore actual damage");
                    server.overworld().setBlock(chosen.getLast(), Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                    var source = server.getPlayerList().getPlayers().getFirst().createCommandSourceStack();
                    JustBuildMod.manager().replaceMaterial(source, "diamond_block", "gold_block");
                });
                c.waitFor(mc -> ClientState.preview() != null);
            }
            // Another session changed the source version while this player was away.
            var builds = new BuildStore(save.getSaveDirectory().resolve("justbuild/builds"));
            try {
                BuildRecord record = builds.loadAll().stream().filter(r -> r.id().equals(buildId)).findFirst().orElseThrow();
                record.addVersion(new BuildRecord.Version("Changed while away", record.current().plan(), System.currentTimeMillis())); builds.save(record);
            } finally { builds.close(); }
            try (var world = save.open()) {
                world.getConnection().waitForChunksRender(); c.waitFor(mc -> ClientState.string("retainedPlan", null) != null);
                command(c, "retry");
                check(c.computeOnClient(mc -> ClientState.preview() == null), "a stale source version resumed an edit");
                check(previewStore.load(actor) != null, "stale source check silently destroyed the plan");
                command(c, "cancel"); check(previewStore.load(actor) == null, "explicit cancellation didn't delete the saved edit");
            }
            check(calls.get() == generated, "resuming or rebuilding alternatives called the AI");
            reconnectPermissions(c, calls, release);
        } catch (Exception e) { throw new AssertionError("Could not verify save and resume", e); }
        finally { release.countDown(); stub.stop(0); restore(configFile, oldConfig); restore(draftsFile, oldDrafts); }
    }

    private static void reconnectPermissions(ClientGameTestContext c, AtomicInteger calls, java.util.concurrent.CountDownLatch release) throws Exception {
        try (var server = c.worldBuilder().createServer()) {
            String name; String actor; Path data;
            try (var connection = server.connect()) {
                connection.waitForChunksRender(); name = c.computeOnClient(mc -> mc.player.getName().getString());
                actor = c.computeOnClient(mc -> mc.player.getUUID().toString());
                server.runCommand("op " + name);
                data = server.computeOnServer(s -> s.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("justbuild/previews"));
                command(c, "build a reconnect marker"); c.waitFor(mc -> ClientState.preview() != null, 1200);
            }
            int generated = calls.get();
            server.runCommand("deop " + name);
            try (var connection = server.connect()) {
                connection.waitForChunksRender(); c.waitFor(mc -> ClientState.string("retainedPlan", null) != null);
                server.runOnServer(s -> JustBuildMod.manager().retry(s.getPlayerList().getPlayers().getFirst().createCommandSourceStack()));
                c.waitTicks(10);
                check(c.computeOnClient(mc -> ClientState.preview() == null), "reconnect bypassed revoked permissions");
                server.runCommand("op " + name); command(c, "retry"); c.waitFor(mc -> ClientState.preview() != null);
                check(calls.get() == generated, "reconnect used another AI call");
                command(c, "preview discard");
                check(new PreviewStore(data).load(actor) == null, "Discard left a durable preview behind");
            }
            try (var connection = server.connect()) {
                connection.waitForChunksRender(); c.waitTicks(10);
                check(c.computeOnClient(mc -> ClientState.string("retainedPlan", null) == null && ClientState.preview() == null), "a discarded preview returned after reconnect");
                command(c, "alternatives build 3 partially finished alternatives");
                for (int i = 0; i < 300 && calls.get() < generated + 2; i++) c.waitTicks(2);
                check(calls.get() == generated + 2, "the interrupted-alternatives fixture did not reach its second call");
                check(new PreviewStore(data).load(actor).choices().size() == 1, "the completed first option was not saved during collection");
                command(c, "preview discard");
                check(new PreviewStore(data).load(actor).choices().size() == 1, "a stale Discard removed newer completed alternatives");
            }
            release.countDown();
            int interrupted = calls.get();
            try (var connection = server.connect()) {
                connection.waitForChunksRender(); c.waitFor(mc -> ClientState.string("retainedPlan", null) != null);
                command(c, "retry"); c.waitFor(mc -> ClientState.preview() != null);
                check(c.computeOnClient(mc -> ClientState.preview().options().size() == 1), "incomplete alternatives were restored");
                check(calls.get() == interrupted, "resuming restarted an unfinished AI call");
                command(c, "preview discard"); command(c, "cancel");
                BlockPos at = c.computeOnClient(mc -> mc.player.blockPosition());
                server.runOnServer(s -> {
                    var source = s.getPlayerList().getPlayers().getFirst().createCommandSourceStack();
                    JustBuildMod.manager().zoneCorner(source, 1, at); JustBuildMod.manager().zoneCorner(source, 2, at);
                    JustBuildMod.manager().start(source, "marker wider than the selected area");
                });
                c.waitFor(mc -> ClientState.string("retainedPlan", null) != null && ClientState.planning() == null, 1200);
                check(c.computeOnClient(mc -> ClientState.preview() == null), "out-of-zone fixture unexpectedly previewed");
            }
            int retainedCalls = calls.get();
            try (var connection = server.connect()) {
                connection.waitForChunksRender(); c.waitFor(mc -> ClientState.string("retainedPlan", null) != null);
                PreviewStore.Choice choice = new PreviewStore(data).load(actor).choices().getFirst();
                server.runOnServer(s -> {
                    var source = s.getPlayerList().getPlayers().getFirst().createCommandSourceStack();
                    JustBuildMod.manager().zoneCorner(source, 1, choice.origin().offset(-3, -1, -3));
                    JustBuildMod.manager().zoneCorner(source, 2, choice.origin().offset(3, -1, 3));
                });
                command(c, "retry"); c.waitFor(mc -> ClientState.preview() != null);
                check(calls.get() == retainedCalls, "resuming an out-of-zone plan called the AI");
                command(c, "preview discard");
            }
        }
    }
    private static List<BlockPos> positions(PreviewStore.Choice choice) {
        PlanRotation rotation = PlanRotation.forFacing(choice.facing().getName());
        return java.util.stream.IntStream.rangeClosed(-1, 1).mapToObj(x -> {
            Cell cell = rotation.apply(new Cell(x, 0, 0)); return choice.origin().offset(cell.x(), cell.y(), cell.z());
        }).toList();
    }
    private static HttpServer stub(AtomicInteger calls, java.util.concurrent.CountDownLatch release) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes(); int call = calls.getAndIncrement();
                if (call == 5) {
                    try { if (!release.await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("Interrupted-alternative fixture was not released"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); exchange.close(); return; }
                }
                String block = switch (call % 3) { case 0 -> "gold_block"; case 1 -> "diamond_block"; default -> "emerald_block"; };
                String plan = "{\"kind\":\"build\",\"title\":\"Saved " + block + "\",\"summary\":\"Three blocks\",\"operations\":[{\"op\":\"fill\",\"block\":\"" + block + "\",\"from\":[-1,0,0],\"to\":[1,0,0]}]}";
                JsonObject message = new JsonObject(); message.addProperty("content", plan);
                JsonObject choice = new JsonObject(); choice.add("message", message); JsonArray choices = new JsonArray(); choices.add(choice);
                JsonObject response = new JsonObject(); response.add("choices", choices);
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start(); return server;
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static void command(ClientGameTestContext c, String command) { c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + command)); c.waitTicks(10); }
    private static byte[] read(Path file) { try { return Files.exists(file) ? Files.readAllBytes(file) : null; } catch (Exception e) { throw new AssertionError(e); } }
    private static void restore(Path file, byte[] bytes) { try { if (bytes == null) Files.deleteIfExists(file); else Files.write(file, bytes); } catch (Exception e) { throw new AssertionError(e); } }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
