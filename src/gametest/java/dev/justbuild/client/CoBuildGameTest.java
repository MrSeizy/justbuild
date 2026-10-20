package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.JustBuildMod;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import dev.justbuild.world.BuildStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Who may change a build. Another player (a server-side stand-in) can't rename it; once the owner adds them to a
 * project with the build, they can (co-building). When they hold the build with a pending edit, the owner is told who
 * is changing it and can release it.
 */
public final class CoBuildGameTest implements FabricClientGameTest {
    private static final String MARKER = """
            {"kind":"build","title":"Team marker","summary":"",
             "operations":[{"op":"fill","block":"gold_block","from":[-1,0,0],"to":[1,0,0]}]}
            """;
    /** The collaborator's edit: the marker plus a lamp, so a preview waits. */
    private static final String LAMP = """
            {"kind":"build","title":"Team marker","summary":"",
             "operations":[{"op":"fill","block":"gold_block","from":[-1,0,0],"to":[1,0,0]},
               {"op":"set","block":"lantern","from":[0,1,0]}]}
            """;
    private static final String OVERLAP = """
            {"kind":"build","title":"Overlapping marker","summary":"",
             "operations":[{"op":"fill","block":"gold_block","from":[-1,-1,0],"to":[1,-1,0]},
               {"op":"set","block":"gold_block","from":[2,0,0]}]}
            """;
    private static final ConcurrentLinkedQueue<String> FRIEND_MESSAGES = new ConcurrentLinkedQueue<>();
    private static final UUID FRIEND = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Override
    public void runTest(ClientGameTestContext context) {
        var file = FabricLoader.getInstance().getConfigDir().resolve("justbuild.json");
        byte[] original;
        try { original = Files.exists(file) ? Files.readAllBytes(file) : null; }
        catch (Exception e) { throw new AssertionError(e); }
        FRIEND_MESSAGES.clear();
        TestAi stub = new TestAi();
        stub.answer(MARKER, false);
        stub.answer(LAMP, false);
        try {
            JustBuildConfig config = JustBuildConfig.defaults();
            var profile = new JustBuildConfig.ModelProfile("Stub", "openai", "stub");
            profile.baseUrl = "http://127.0.0.1:" + stub.server.getAddress().getPort() + "/v1";
            config.replaceProfiles(List.of(profile), "Stub");
            config.playerAi = "off"; // The simulated teammate uses this fixture's server-side AI.
            config.previewAbove = 0;
            config.undoHistory = 1;
            config.detail = "quick";
            config.creativeDirection = false;
            new ConfigStore(file).save(config);
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                world.getClientLevel().waitForChunksRender();
                BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition().above(10));
                String player = context.computeOnClient(mc -> mc.player.getName().getString());
                UUID owner = context.computeOnClient(mc -> mc.player.getUUID());
                world.getServer().runOnServer(server -> {
                    for (BlockPos pos : BlockPos.betweenClosed(at.offset(-6, -1, -6), at.offset(6, 6, 6))) {
                        server.overworld().setBlock(pos, pos.getY() == at.getY() - 1 ? Blocks.STONE.defaultBlockState()
                                : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                });
                world.getServer().runCommand("gamemode creative " + player);
                world.getServer().runCommand("tp " + player + " " + (at.getX() + 0.5) + " " + (at.getY() + 4)
                        + " " + (at.getZ() + 6.5) + " 180 25");
                context.waitTicks(20);
                command(context, "zone 1 " + position(at.offset(-1, -1, 0)));
                command(context, "zone 2 " + position(at.offset(1, -1, 0)));
                command(context, "build a team marker");
                context.waitFor(mc -> ClientState.preview() != null, 1200);
                command(context, "confirm");
                context.waitFor(mc -> !ClientState.builds().isEmpty() && ClientState.preview() == null, 1200);
                String id = context.computeOnClient(mc -> ClientState.builds().getFirst().id());

                // Another player may select the build but not change it.
                friend(world, id, (m, s) -> m.renameBuild(s, "Taken over"));
                check("Team marker".equals(title(world, id)), "a player outside the build's team renamed it");
                FRIEND_MESSAGES.clear();
                friend(world, id, (m, s) -> {
                    m.partCorner(s, 1, at.west()); m.partCorner(s, 2, at.east());
                    m.lockPart(s, "Unauthorized lock"); m.clearPart(s);
                });
                check(friendSaw("cannot lock another player's building"), "a non-owner could lock the owner's build");

                // The owner adds the build to a project and the other player to its team: now they may.
                command(context, "project create Riverside");
                command(context, "select " + id);
                command(context, "project add");
                // Invited offline by UUID, they are named as this server last saw them join.
                world.getServer().runOnServer(server -> server.services().nameToIdCache().add(new NameAndId(FRIEND, "Robin")));
                command(context, "project invite " + FRIEND);
                check(saw(context, "Robin can now change the buildings of “Riverside”"), "the invite did not name the cached player");
                friend(world, id, (m, s) -> m.renameBuild(s, "Shared marker"));
                check("Shared marker".equals(title(world, id)), "a project collaborator could not rename the build");

                // The collaborator starts an edit and leaves it waiting: the owner is told who holds it and can release it.
                FRIEND_MESSAGES.clear();
                friend(world, id, (m, s) -> m.edit(s, "#" + id + " add a lamp"));
                context.waitFor(mc -> friendSaw("would replace"), 1200);
                check(block(world, at.above(), Blocks.AIR), "the collaborator's preview placed its lamp");
                command(context, "undo");
                check(saw(context, "is changing it"), "the owner was not told who holds the build");
                command(context, "release #" + id);
                check(saw(context, "Released “Shared marker”"), "the owner could not release the build");
                command(context, "undo");
                check(saw(context, "Undo removes “Shared marker”"), "the build was still busy after the release");
                command(context, "undo cancel");

                // An editor cannot remove the owner's build and re-share it through a project they control.
                String project = context.computeOnClient(mc -> ClientState.projects().getFirst().id());
                friend(world, id, (m, s) -> { m.project(s, "select " + project); m.project(s, "remove"); });
                command(context, "status");
                check(context.computeOnClient(mc -> ClientState.projects().stream().filter(p -> p.id().equals(project))
                        .findFirst().orElseThrow().builds().contains(id)), "collaborator removed the owner's build from its project");
                friend(world, id, (m, s) -> { m.project(s, "create Stolen"); m.project(s, "add"); });

                // Revocation invalidates an already generated local edit preview, releasing its hold.
                friend(world, id, (m, s) -> m.replaceMaterial(s, "gold_block", "diamond_block"));
                check(friendPreviewWaiting(world, id), "the collaborator's local edit was not pending");
                command(context, "project uninvite " + FRIEND);
                check(!friendPreviewWaiting(world, id), "revocation left the owner's build busy");
                friend(world, id, (m, s) -> { m.confirm(s); m.retry(s); m.confirm(s); m.renameBuild(s, "Stolen"); });
                context.waitTicks(20);
                check(block(world, at, Blocks.GOLD_BLOCK), "a revoked preview or retry changed the owner's build");
                check("Shared marker".equals(title(world, id)), "collaborator kept access through another project");

                // Offline, only a name this server cached at a login resolves; nothing is looked up elsewhere.
                command(context, "project invite Nobody_Here");
                check(saw(context, "No player named “Nobody_Here”"), "an unknown offline name was accepted");

                // A collaborator's completed edit creates their own undo step. Revocation also blocks that undo.
                command(context, "project invite Robin");
                friend(world, id, (m, s) -> { m.replaceMaterial(s, "gold_block", "diamond_block"); m.confirm(s); });
                for (int i = 0; i < 100 && friendPreviewWaiting(world, id); i++) context.waitTicks(2);
                check(block(world, at, Blocks.DIAMOND_BLOCK), "authorized co-build did not finish");
                friend(world, id, (m, s) -> m.undo(s));
                command(context, "project uninvite " + FRIEND);
                friend(world, id, (m, s) -> { m.confirmUndo(s); m.undo(s); m.confirmUndo(s); });
                context.waitTicks(20);
                check(block(world, at, Blocks.DIAMOND_BLOCK), "a revoked collaborator undid the owner's build");

                // One player's history cap must preserve another player's exact undo predecessor.
                world.getServer().runOnServer(server -> {
                    var source = server.getPlayerList().getPlayer(owner).createCommandSourceStack();
                    JustBuildMod.manager().replaceMaterial(source, "diamond_block", "emerald_block");
                    JustBuildMod.manager().confirm(source);
                });
                awaitIdle(context, world, id);
                check(marker(world, at, Blocks.EMERALD_BLOCK), "the owner's intervening edit did not complete");
                command(context, "undo");
                command(context, "undo confirm");
                awaitIdle(context, world, id);
                check(marker(world, at, Blocks.DIAMOND_BLOCK), "the owner could not undo back to the collaborator's version");
                command(context, "project invite " + FRIEND);
                friend(world, id, (m, s) -> { m.undo(s); m.confirmUndo(s); });
                awaitIdle(context, world, id);
                check(marker(world, at, Blocks.GOLD_BLOCK), "trimming the owner's history broke the collaborator's older undo");
                friend(world, id, (m, s) -> { m.replaceMaterial(s, "gold_block", "diamond_block"); m.confirm(s); });
                awaitIdle(context, world, id);
                check(marker(world, at, Blocks.DIAMOND_BLOCK), "the collaborator could not edit again after undo");

                // A late AI answer must not bypass revocation through Auto-build.
                command(context, "project invite " + FRIEND);
                Answer late = stub.answer(LAMP, true);
                friend(world, id, (m, s) -> { m.autoBuild(s, "on"); m.edit(s, "#" + id + " add the lamp automatically"); });
                context.waitFor(mc -> late.started.getCount() == 0, 1200);
                command(context, "project uninvite " + FRIEND);
                late.release.countDown();
                context.waitFor(mc -> late.finished.getCount() == 0, 1200);
                context.waitTicks(20);
                friend(world, id, (m, s) -> { m.confirm(s); m.retry(s); m.confirm(s); });
                context.waitTicks(10);
                check(block(world, at, Blocks.DIAMOND_BLOCK) && block(world, at.above(), Blocks.AIR),
                        "a revoked in-flight Auto-build edit changed the owner's blocks");
                check(!friendPreviewWaiting(world, id), "the cancelled Auto-build edit kept its hold");

                // Releasing a collecting alternatives batch must discard its completed options too.
                command(context, "project invite " + FRIEND);
                stub.answer(LAMP, false);
                Answer second = stub.answer(LAMP, true);
                friend(world, id, (m, s) -> m.startAlternatives(s, true, 2, "#" + id + " add a lamp"));
                context.waitFor(mc -> second.started.getCount() == 0, 1200);
                check(block(world, at, Blocks.DIAMOND_BLOCK) && block(world, at.above(), Blocks.AIR),
                        "Auto-build placed an alternative while options were still collecting");
                command(context, "release #" + id);
                second.release.countDown();
                context.waitFor(mc -> second.finished.getCount() == 0, 1200);
                context.waitTicks(10);
                friend(world, id, (m, s) -> { m.chooseAlternative(s, 1); m.confirm(s); });
                check(block(world, at, Blocks.DIAMOND_BLOCK) && block(world, at.above(), Blocks.AIR),
                        "a released alternatives batch could still be confirmed");
                friend(world, id, (m, s) -> { m.replaceMaterial(s, "diamond_block", "emerald_block"); m.confirm(s); });
                awaitIdle(context, world, id);
                check(block(world, at, Blocks.EMERALD_BLOCK), "a released alternatives batch swallowed the next authorized edit");

                // Confirm and revoke on the same server turn, before the placement queue gets a tick.
                world.getServer().runOnServer(server -> {
                    var manager = JustBuildMod.manager();
                    CommandSourceStack source = new FriendPlayer(server.overworld()).createCommandSourceStack();
                    manager.selectBuild(source, id);
                    manager.replaceMaterial(source, "emerald_block", "diamond_block");
                    manager.confirm(source);
                    check(manager.isBusy(id), "the queued co-build edit did not acquire its hold");
                    manager.project(server.getPlayerList().getPlayer(owner).createCommandSourceStack(), "uninvite " + FRIEND);
                });
                awaitIdle(context, world, id);
                check(block(world, at, Blocks.EMERALD_BLOCK), "a queued edit placed after its author lost access");

                // Leaving voluntarily removes the same edit authority, including a retained discarded plan.
                command(context, "project invite " + FRIEND);
                friend(world, id, (m, s) -> {
                    m.project(s, "select " + project);
                    m.replaceMaterial(s, "emerald_block", "diamond_block");
                    m.cancel(s);
                    m.project(s, "leave");
                    m.retry(s);
                    m.confirm(s);
                    m.renameBuild(s, "Left but still editing");
                });
                context.waitTicks(10);
                check(block(world, at, Blocks.EMERALD_BLOCK) && "Shared marker".equals(title(world, id)),
                        "a player who left the project could still change its building");

                // Collaborators can edit around an owner's lock but cannot remove it.
                command(context, "project invite " + FRIEND);
                command(context, "box 1 " + position(at));
                command(context, "box 2 " + position(at));
                command(context, "lock Owner-only marker");
                String lock = context.computeOnClient(mc -> ClientState.locks().stream()
                        .filter(l -> l.name().equals("Owner-only marker")).findFirst().orElseThrow().id());
                friend(world, id, (m, s) -> {
                    m.unlockPart(s, lock);
                    m.replaceMaterial(s, "emerald_block", "diamond_block");
                    m.confirm(s);
                });
                awaitIdle(context, world, id);
                check(block(world, at, Blocks.EMERALD_BLOCK), "a collaborator removed or wrote through the owner's lock");
                check(block(world, at.west(), Blocks.DIAMOND_BLOCK) && block(world, at.east(), Blocks.DIAMOND_BLOCK),
                        "the lock test did not perform the authorized surrounding edit");
                command(context, "unlock " + lock);
                check(context.computeOnClient(mc -> ClientState.locks().stream().noneMatch(l -> l.id().equals(lock))),
                        "the lock owner could not remove their lock");

                // A new build belongs to its author, but overlapping cells still belong to the other building too.
                world.getServer().runOnServer(server -> {
                    var source = server.getPlayerList().getPlayer(owner).createCommandSourceStack();
                    JustBuildMod.manager().replaceMaterial(source, "diamond_block", "emerald_block");
                    JustBuildMod.manager().confirm(source);
                });
                awaitIdle(context, world, id);
                prepareOverlap(context, world, stub, id, at);
                world.getServer().runOnServer(server -> {
                    var manager = JustBuildMod.manager();
                    manager.confirm(new FriendPlayer(server.overworld()).createCommandSourceStack());
                    manager.project(server.getPlayerList().getPlayer(owner).createCommandSourceStack(), "uninvite " + FRIEND);
                });
                String own = friendBuild(world);
                awaitIdle(context, world, own);
                check(marker(world, at, Blocks.EMERALD_BLOCK), "a queued own build wrote over a former teammate's building");
                BlockPos outside = block(world, at.west(2).above(), Blocks.GOLD_BLOCK) ? at.west(2).above() : at.east(2).above();
                check(block(world, outside, Blocks.GOLD_BLOCK), "the own-build test did not place its unprotected block");

                // Undo of that own build must also leave later changes to the former teammate's cells intact.
                world.getServer().runOnServer(server -> server.overworld().setBlock(at, Blocks.DIAMOND_BLOCK.defaultBlockState(),
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
                friend(world, own, (m, s) -> { m.undo(s); m.confirmUndo(s); });
                awaitIdle(context, world, own);
                check(block(world, at, Blocks.DIAMOND_BLOCK), "undo of an own build overwrote a former teammate's block");
                check(block(world, outside, Blocks.AIR), "undo failed to remove the own build's unprotected block");

                // Saved build zones are editable metadata; moving one cannot hide its existing protected cells.
                world.getServer().runOnServer(server -> {
                    var source = server.getPlayerList().getPlayer(owner).createCommandSourceStack();
                    JustBuildMod.manager().selectBuild(source, id);
                    JustBuildMod.manager().zoneCorner(source, 1, at.offset(100, -1, 0));
                    JustBuildMod.manager().zoneCorner(source, 2, at.offset(102, -1, 0));
                });
                prepareOverlap(context, world, stub, id, at);
                friend(world, id, (m, s) -> m.confirm(s));
                own = friendBuild(world);
                awaitIdle(context, world, own);
                check(block(world, at, Blocks.DIAMOND_BLOCK) && block(world, at.west(), Blocks.EMERALD_BLOCK)
                                && block(world, at.east(), Blocks.EMERALD_BLOCK),
                        "moving a saved build zone hid its existing cells from ownership protection");
                check(block(world, outside, Blocks.GOLD_BLOCK), "the moved-zone test placed no unprotected block");
            }
        } catch (Exception e) {
            throw new AssertionError("Could not exercise co-building", e);
        } finally {
            stub.close();
            try {
                if (original == null) Files.deleteIfExists(file);
                else Files.write(file, original);
            } catch (Exception e) { throw new AssertionError("Could not restore test config", e); }
        }
    }

    /** Runs a session action as the other player, on the server thread. */
    private static void friend(TestSingleplayerContext world, String id,
            java.util.function.BiConsumer<dev.justbuild.session.BuildSessionManager, CommandSourceStack> action) {
        world.getServer().runOnServer(server -> {
            CommandSourceStack source = new FriendPlayer(server.overworld()).createCommandSourceStack();
            JustBuildMod.manager().selectBuild(source, id);
            action.accept(JustBuildMod.manager(), source);
        });
    }

    private static boolean friendPreviewWaiting(TestSingleplayerContext world, String id) {
        return world.getServer().computeOnServer(s -> JustBuildMod.manager().isBusy(id));
    }

    private static String title(TestSingleplayerContext world, String id) {
        return world.getServer().computeOnServer(s -> JustBuildMod.manager().buildTitle(id));
    }

    private static boolean block(TestSingleplayerContext world, BlockPos pos, Block block) {
        return world.getServer().computeOnServer(s -> s.overworld().getBlockState(pos).is(block));
    }

    private static boolean saw(ClientGameTestContext context, String text) {
        return context.computeOnClient(mc -> ClientState.lines().stream().anyMatch(line -> line.getString().contains(text)));
    }

    /** Records the fake player's messages so waits observe the actual preview transition, not only its busy hold. */
    private static final class FriendPlayer extends FakePlayer {
        FriendPlayer(ServerLevel level) { super(level, new GameProfile(FRIEND, "Robin")); }
        @Override public net.minecraft.server.permissions.PermissionSet permissions() {
            return net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER;
        }
        @Override public void sendSystemMessage(Component message) { FRIEND_MESSAGES.add(message.getString()); }
    }

    private static void prepareOverlap(ClientGameTestContext context, TestSingleplayerContext world, TestAi stub,
            String id, BlockPos at) {
        FRIEND_MESSAGES.clear();
        stub.answer(OVERLAP, false);
        friend(world, id, (m, s) -> {
            m.autoBuild(s, "off");
            m.project(s, "clear");
            m.zoneCorner(s, 1, at.offset(-2, -1, 0));
            m.zoneCorner(s, 2, at.offset(2, -1, 0));
            m.start(s, "build an overlapping marker");
        });
        context.waitFor(mc -> friendSaw("would place"), 1200);
    }

    private static String friendBuild(TestSingleplayerContext world) {
        return world.getServer().computeOnServer(server -> new BuildStore(server.getWorldPath(LevelResource.ROOT)
                        .resolve("justbuild/builds")).loadAll().stream()
                .filter(record -> record.owner().equals(FRIEND.toString()) && record.title().equals("Overlapping marker"))
                .findFirst().orElseThrow(() -> new AssertionError("the friend's own build was not queued")).id());
    }

    private static boolean marker(TestSingleplayerContext world, BlockPos at, Block block) {
        return block(world, at, block) && block(world, at.west(), block) && block(world, at.east(), block);
    }

    private static boolean friendSaw(String text) {
        return FRIEND_MESSAGES.stream().anyMatch(line -> line.contains(text));
    }

    private static void awaitIdle(ClientGameTestContext context, TestSingleplayerContext world, String id) {
        for (int i = 0; i < 100 && friendPreviewWaiting(world, id); i++) context.waitTicks(2);
        check(!friendPreviewWaiting(world, id), "the edit or its rollback did not finish");
    }

    private static final class Answer {
        final String plan;
        final CountDownLatch started = new CountDownLatch(1), finished = new CountDownLatch(1), release;
        Answer(String plan, boolean delayed) { this.plan = plan; release = new CountDownLatch(delayed ? 1 : 0); }
    }

    /** Each scripted response can be held until a permission change has completed on the server thread. */
    private static final class TestAi implements AutoCloseable {
        final HttpServer server;
        final ConcurrentLinkedQueue<Answer> waiting = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Answer> all = new ConcurrentLinkedQueue<>();
        TestAi() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/v1/chat/completions", exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    Answer answer = waiting.poll();
                    if (answer == null) { exchange.sendResponseHeaders(500, -1); exchange.close(); return; }
                    answer.started.countDown();
                    try {
                        if (!answer.release.await(45, TimeUnit.SECONDS)) return;
                        JsonObject message = new JsonObject(); message.addProperty("content", answer.plan);
                        JsonObject choice = new JsonObject(); choice.add("message", message);
                        JsonArray choices = new JsonArray(); choices.add(choice);
                        JsonObject reply = new JsonObject(); reply.add("choices", choices);
                        byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (java.io.IOException cancelled) {
                        // The permission change may already have cancelled the client's HTTP call.
                    } finally {
                        exchange.close();
                        answer.finished.countDown();
                    }
                });
                server.start();
            } catch (Exception e) { throw new AssertionError("Could not start test AI", e); }
        }
        Answer answer(String plan, boolean delayed) {
            Answer answer = new Answer(plan, delayed); waiting.add(answer); all.add(answer); return answer;
        }
        @Override public void close() { all.forEach(a -> a.release.countDown()); server.stop(0); }
    }

    private static String position(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }
    private static void command(ClientGameTestContext context, String text) {
        context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + text));
        context.waitTicks(10);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
