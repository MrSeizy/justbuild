package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.JustBuildMod;
import dev.justbuild.config.ConfigStore;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.session.PreviewStore;
import dev.justbuild.world.BuildRecord;
import dev.justbuild.world.BuildStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Revising a waiting preview with the AI. The revised plan replaces the preview at its moved position. Previous steps
 * back without a call. Stop and Discard end only what they say. Failed and Auto-build revisions place nothing.
 * Alternatives keep separate histories. Instant Build, Guided Build, and Retry all use the revised plan.
 */
public final class PreviewRevisionGameTest implements FabricClientGameTest {
    private static final String GOLD = plan("Marker", "gold_block", 1);
    private static final String LAPIS = plan("Lapis marker", "lapis_block", 1);
    private static final String WIDE = plan("Wide marker", "lapis_block", 9);
    private static final String QUESTION = "{\"kind\":\"question\",\"question\":\"Which stone?\",\"options\":[\"Lapis\",\"Gold\"]}";
    private static final String REVISING = "You are revising a proposed build that has not been placed yet";
    /** In the first revision request and in its review or size-retry rounds alike. */
    private static final String PROPOSAL = "proposed build that has not been placed yet";

    private static String plan(String title, String block, int half) {
        return "{\"kind\":\"build\",\"title\":\"" + title + "\",\"summary\":\"\",\"operations\":[{\"op\":\"fill\",\"block\":\""
                + block + "\",\"from\":[-" + half + ",0,0],\"to\":[" + half + ",0,0]}]}";
    }

    @Override
    public void runTest(ClientGameTestContext c) {
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve("justbuild.json");
        Path draftsFile = configFile.resolveSibling("justbuild-drafts.json");
        byte[] oldConfig = read(configFile), oldDrafts = read(draftsFile);
        List<String> bodies = new CopyOnWriteArrayList<>();
        BlockingQueue<Boolean> gate = new LinkedBlockingQueue<>();
        HttpServer stub = stub(bodies, gate);
        try {
            JustBuildConfig config = JustBuildConfig.defaults();
            var profile = new JustBuildConfig.ModelProfile("Stub", "openai", "stub");
            profile.baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1";
            config.replaceProfiles(List.of(profile), "Stub");
            config.previewAbove = 0;
            config.detail = "quick";
            config.creativeDirection = false;
            config.playerAi = "off";
            new ConfigStore(configFile).save(config);
            TestWorldSave save;
            String actor;
            int callsBeforeRestart;
            try (TestSingleplayerContext world = c.worldBuilder().create()) {
                save = world.getWorldSave();
                world.getConnection().waitForChunksRender();
                actor = c.computeOnClient(mc -> mc.player.getUUID().toString());
                BlockPos at = c.computeOnClient(mc -> mc.player.blockPosition().above(10));
                String player = c.computeOnClient(mc -> mc.player.getName().getString());
                world.getServer().runOnServer(server -> {
                    for (BlockPos pos : BlockPos.betweenClosed(at.offset(-10, -1, -10), at.offset(10, 8, 10)))
                        server.overworld().setBlock(pos, pos.getY() == at.getY() - 1 ? Blocks.STONE.defaultBlockState()
                                : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                });
                world.getServer().runCommand("gamemode creative " + player);
                world.getServer().runCommand("tp " + player + " " + (at.getX() + 0.5) + " " + at.getY() + " "
                        + (at.getZ() + 3.0) + " 180 10");
                c.waitTicks(20);
                command(c, "zone 1 " + position(at.offset(-3, -1, -3)));
                command(c, "zone 2 " + position(at.offset(3, -1, 3)));

                // Before a preview exists the request box sends a new build, and nothing offers Revise.
                openCreate(c);
                check(button(c, "Send") != null && button(c, "Revise") == null, "Revise was offered with no preview waiting");
                type(c, "a marker", "Send");
                c.waitFor(mc -> ClientState.preview() != null, 1200);
                check(c.computeOnClient(mc -> ClientState.preview().title().equals("Marker") && ClientState.preview().revisable()),
                        "a new-build preview was not revisable");
                String start = c.computeOnClient(mc -> ClientState.preview().position());
                command(c, "preview move 1 0 0");
                c.waitFor(mc -> !ClientState.preview().position().equals(start), 200);
                String moved = c.computeOnClient(mc -> ClientState.preview().position());

                // 1. Revise through the panel: the AI gets the unplaced plan, and the result keeps the moved frame.
                openCreate(c);
                check(Boolean.TRUE.equals(button(c, "Revise")), "a waiting new-build preview did not offer Revise");
                type(c, "make it lapis", "Revise");
                c.waitFor(mc -> ClientState.preview() != null && ClientState.preview().revisions() == 1, 1200);
                check(c.computeOnClient(mc -> ClientState.preview().title().equals("Lapis marker")
                        && ClientState.preview().position().equals(moved) && ClientState.preview().previous()),
                        "the revision did not replace the preview at its moved position");
                String asked = bodies.getLast();
                check(asked.contains(REVISING) && asked.contains("gold_block") && asked.contains("make it lapis"),
                        "the AI did not receive the unplaced plan and the change");
                check(count(world, at, Blocks.LAPIS_BLOCK) == 0 && count(world, at, Blocks.GOLD_BLOCK) == 0,
                        "revising placed blocks");

                // 2. Previous restores the earlier plan, title, and position without another call.
                int calls = bodies.size();
                openCreate(c);
                c.clickScreenButton("Previous");
                c.waitFor(mc -> ClientState.preview().revisions() == 0, 200);
                check(c.computeOnClient(mc -> ClientState.preview().title().equals("Marker") && !ClientState.preview().previous()
                        && ClientState.preview().position().equals(moved)), "Previous did not restore the earlier version");
                check(bodies.size() == calls, "Previous called the AI");
                c.runOnClient(mc -> mc.gui.setScreen(null));

                // 2b. A revision's clarifying question can be answered from the preview's panel.
                command(c, "revise make it lapis but ask first");
                c.waitFor(mc -> ClientState.question() != null, 600);
                openCreate(c);
                c.clickScreenButton("Lapis");
                c.waitFor(mc -> ClientState.preview().revisions() == 1, 600);
                check(bodies.getLast().contains("Player answered: Lapis"), "the answer did not reach the AI");
                c.runOnClient(mc -> mc.gui.setScreen(null));
                command(c, "preview previous");
                c.waitFor(mc -> ClientState.preview().revisions() == 0, 200);

                // 3. While a revision runs, placement and preview controls wait; Stop keeps the preview and its file.
                command(c, "revise make it lapis slowly");
                c.waitFor(mc -> ClientState.planning() != null, 200);
                openCreate(c);
                check(Boolean.FALSE.equals(button(c, "Instant Build")) && Boolean.FALSE.equals(button(c, "Guided Build"))
                        && Boolean.FALSE.equals(button(c, "Revise")) && button(c, "Stop") != null,
                        "the panel did not pause for the revision");
                c.runOnClient(mc -> mc.gui.setScreen(null));
                command(c, "confirm");
                command(c, "preview move 1 0 0");
                command(c, "construction start guide");
                check(count(world, at, Blocks.GOLD_BLOCK) == 0, "Instant Build placed during a revision");
                check(c.computeOnClient(mc -> ClientState.preview().position().equals(moved)), "a move applied during a revision");
                check(!constructing(world), "Guided Build started during a revision");
                command(c, "cancel");
                c.waitFor(mc -> ClientState.planning() == null, 200);
                gate.add(true);
                c.waitTicks(40);
                check(c.computeOnClient(mc -> ClientState.preview() != null && ClientState.preview().title().equals("Marker")
                        && ClientState.preview().revisions() == 0), "Stop changed the preview, or the stopped revision arrived");
                check(saved(save, actor) != null, "Stop removed the saved preview");

                // 4. A revision that crosses the zone keeps the preview and retains nothing.
                int before = bodies.size();
                command(c, "revise make it wide");
                c.waitFor(mc -> bodies.size() > before && ClientState.planning() == null, 600);
                c.waitTicks(10);
                check(c.computeOnClient(mc -> ClientState.preview().title().equals("Marker")
                        && ClientState.string("retainedPlan", null) == null
                        && ClientState.lines().stream().anyMatch(line -> line.getString().contains("Preview unchanged"))),
                        "a zone-crossing revision replaced or retained the plan, or gave no reason");

                // 5. Auto-build never places a revision.
                command(c, "autobuild on");
                command(c, "revise make it lapis");
                c.waitFor(mc -> ClientState.preview() != null && ClientState.preview().revisions() == 1, 600);
                c.waitTicks(20);
                check(count(world, at, Blocks.LAPIS_BLOCK) == 0, "Auto-build placed a revision");
                command(c, "autobuild off");

                // 6. Discard during a revision ends both; the late reply brings nothing back.
                command(c, "revise make it lapis slowly");
                c.waitFor(mc -> ClientState.planning() != null, 200);
                command(c, "preview discard");
                c.waitFor(mc -> ClientState.planning() == null && ClientState.preview() == null, 200);
                gate.add(true);
                c.waitTicks(40);
                check(c.computeOnClient(mc -> ClientState.preview() == null) && count(world, at, Blocks.LAPIS_BLOCK) == 0,
                        "a discarded preview's revision came back");

                // 7. With alternatives, Revise changes the displayed option and each option keeps its own history.
                command(c, "alternatives build 2 a marker");
                c.waitFor(mc -> ClientState.preview() != null && ClientState.preview().options().size() == 2, 1200);
                command(c, "preview option 2");
                c.waitFor(mc -> ClientState.preview().option() == 1, 200);
                command(c, "revise make it lapis");
                c.waitFor(mc -> ClientState.preview().revisions() == 1, 600);
                check(c.computeOnClient(mc -> ClientState.preview().options().equals(List.of("Marker", "Lapis marker"))
                        && ClientState.preview().option() == 1), "revising changed the wrong option");
                command(c, "preview option 1");
                c.waitFor(mc -> ClientState.preview().option() == 0, 200);
                check(c.computeOnClient(mc -> ClientState.preview().title().equals("Marker")
                        && ClientState.preview().revisions() == 0 && !ClientState.preview().previous()),
                        "the other option picked up the revision");
                command(c, "preview option 2");
                c.waitFor(mc -> ClientState.preview().option() == 1, 200);
                check(c.computeOnClient(mc -> ClientState.preview().previous()), "switching options lost the revised option's history");

                // 8. Instant Build places the revised plan (its stored request is checked after the world closes).
                command(c, "confirm");
                c.waitFor(mc -> ClientState.preview() == null && ClientState.placing() == null, 600);
                for (int i = 0; i < 100 && count(world, at, Blocks.LAPIS_BLOCK) < 3; i++) c.waitTicks(2);
                check(count(world, at, Blocks.LAPIS_BLOCK) == 3, "Instant Build did not place the revised plan");

                // 9. Guided Build takes the revised blueprint, and Retry brings that blueprint back afterwards.
                command(c, "select clear");
                command(c, "zone 1 " + position(at.offset(4, -1, -8)));
                command(c, "zone 2 " + position(at.offset(8, -1, -4)));
                type(c, "a marker", "Send");
                c.waitFor(mc -> ClientState.preview() != null, 1200);
                command(c, "revise make it lapis");
                c.waitFor(mc -> ClientState.preview().revisions() == 1, 600);
                command(c, "construction start guide");
                for (int i = 0; i < 100 && !constructing(world); i++) c.waitTicks(2);
                check(world.getServer().computeOnServer(server -> {
                    var project = JustBuildMod.manager().construction().project(server.getPlayerList().getPlayers().getFirst());
                    return project != null && project.blueprint.values().stream().anyMatch(s -> s.is(Blocks.LAPIS_BLOCK))
                            && project.blueprint.values().stream().noneMatch(s -> s.is(Blocks.GOLD_BLOCK));
                }), "Guided Build did not use the revised blueprint");
                command(c, "construction finish");
                for (int i = 0; i < 100 && constructing(world); i++) c.waitTicks(2);
                command(c, "retry");
                c.waitFor(mc -> ClientState.preview() != null, 200);
                check(c.computeOnClient(mc -> ClientState.preview().title().equals("Lapis marker")
                        && !ClientState.preview().previous()), "Retry did not bring back the revised plan");

                // 10. Revise once more, then restart with the revised preview waiting.
                command(c, "revise make it lapis");
                c.waitFor(mc -> ClientState.preview().revisions() == 1, 600);
                callsBeforeRestart = bodies.size();
            }
            try (TestSingleplayerContext world = save.open()) {
                world.getConnection().waitForChunksRender();
                c.waitFor(mc -> ClientState.string("retainedPlan", null) != null);
                command(c, "retry");
                c.waitFor(mc -> ClientState.preview() != null, 600);
                check(c.computeOnClient(mc -> ClientState.preview().title().equals("Lapis marker")
                        && ClientState.preview().revisions() == 0 && !ClientState.preview().previous()),
                        "Retry after a restart did not restore the latest revision");
                check(bodies.size() == callsBeforeRestart, "Retry after a restart called the AI");
                command(c, "cancel");
            }
            var builds = new BuildStore(save.getSaveDirectory().resolve("justbuild/builds"));
            try {
                BuildRecord placed = builds.loadAll().stream()
                        .filter(r -> r.current().request().contains("(revised: make it lapis)")).findFirst()
                        .orElseThrow(() -> new AssertionError("the placed build did not record its revision"));
                check(placed.typedRequest() != null && placed.typedRequest().contains("a marker")
                        && !placed.typedRequest().contains("lapis"),
                        "the placed build's typed request was not the original: " + placed.typedRequest());
            } finally {
                builds.close();
            }
        } catch (Exception e) {
            throw new AssertionError("Could not verify preview revisions", e);
        } finally {
            stub.stop(0);
            restore(configFile, oldConfig);
            restore(draftsFile, oldDrafts);
        }
    }

    /**
     * New builds get the gold marker. Revisions get the lapis marker, or a zone-crossing one for "make it wide".
     * "ask first" asks a question until it is answered. A revision asking "slowly" waits for the test to release it.
     */
    private static HttpServer stub(List<String> bodies, BlockingQueue<Boolean> gate) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "preview-revision-stub");
                thread.setDaemon(true);
                return thread;
            }));
            server.createContext("/v1/chat/completions", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                bodies.add(body);
                boolean revision = body.contains(PROPOSAL);
                if (revision && body.contains("slowly")) {
                    try { gate.poll(60, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                JsonObject message = new JsonObject();
                message.addProperty("content", !revision ? GOLD : body.contains("make it wide") ? WIDE
                        : body.contains("ask first") && !body.contains("Player answered") ? QUESTION : LAPIS);
                JsonObject choice = new JsonObject();
                choice.add("message", message);
                JsonArray choices = new JsonArray();
                choices.add(choice);
                JsonObject reply = new JsonObject();
                reply.add("choices", choices);
                byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (java.io.IOException ignored) {
                    // A stopped request may have closed the connection already.
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new AssertionError("Could not start test AI", e);
        }
    }

    private static void openCreate(ClientGameTestContext c) {
        c.runOnClient(mc -> mc.gui.setScreen(new JustBuildScreen()));
        c.clickScreenButton("Create");
        c.waitTicks(5);
    }

    /** Types into the Create tab's request box and presses the button beside it. */
    private static void type(ClientGameTestContext c, String text, String button) {
        openCreate(c);
        c.runOnClient(mc -> mc.gui.screen().children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                .findFirst().orElseThrow().setValue(text));
        c.clickScreenButton(button);
        c.waitTicks(10);
        c.runOnClient(mc -> mc.gui.setScreen(null));
    }

    /** Whether the open screen's button with this label is active, or null when there is none. */
    private static Boolean button(ClientGameTestContext c, String label) {
        return c.computeOnClient(mc -> mc.gui.screen().children().stream()
                .filter(child -> child instanceof GlassButton b && b.getMessage().getString().equals(label))
                .map(child -> ((GlassButton) child).active).findFirst().orElse(null));
    }

    private static int count(TestSingleplayerContext world, BlockPos at, Block block) {
        return world.getServer().computeOnServer(server -> {
            int n = 0;
            for (BlockPos pos : BlockPos.betweenClosed(at.offset(-10, -1, -10), at.offset(10, 3, 10)))
                if (server.overworld().getBlockState(pos).is(block)) n++;
            return n;
        });
    }

    private static boolean constructing(TestSingleplayerContext world) {
        return world.getServer().computeOnServer(server -> JustBuildMod.manager().construction()
                .active(server.getPlayerList().getPlayers().getFirst()));
    }

    private static PreviewStore.Saved saved(TestWorldSave save, String actor) throws java.io.IOException {
        return new PreviewStore(save.getSaveDirectory().resolve("justbuild/previews")).load(actor);
    }

    private static byte[] read(Path file) {
        try { return Files.exists(file) ? Files.readAllBytes(file) : null; }
        catch (java.io.IOException e) { throw new AssertionError(e); }
    }

    private static void restore(Path file, byte[] bytes) {
        try { if (bytes == null) Files.deleteIfExists(file); else Files.write(file, bytes); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
    }

    private static String position(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }

    private static void command(ClientGameTestContext c, String text) {
        c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + text));
        c.waitTicks(10);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
