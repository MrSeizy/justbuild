package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Exercise the former two-minute deadline with real elapsed time, including a paused client. */
public final class PersistentPreviewTest implements FabricClientGameTest {
    private static final String PLAN = """
            {"kind":"build","title":"Waiting marker","summary":"Three gold blocks",
             "operations":[{"op":"fill","block":"gold_block","from":[-1,0,0],"to":[1,0,0]}]}
            """;

    @Override
    public void runTest(ClientGameTestContext context) {
        var file = FabricLoader.getInstance().getConfigDir().resolve("justbuild.json");
        byte[] original;
        try { original = Files.exists(file) ? Files.readAllBytes(file) : null; }
        catch (Exception e) { throw new AssertionError(e); }
        AtomicInteger calls = new AtomicInteger();
        HttpServer stub = stub(calls);
        ConfigStore store = new ConfigStore(file);
        try {
            JustBuildConfig config = JustBuildConfig.defaults();
            var profile = new JustBuildConfig.ModelProfile("Stub", "openai", "stub");
            profile.baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1";
            config.replaceProfiles(List.of(profile), "Stub");
            config.previewAbove = 0;
            config.detail = "quick";
            config.creativeDirection = false;
            store.save(config);
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                world.getClientLevel().waitForChunksRender();
                BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition().above(10));
                String player = context.computeOnClient(mc -> mc.player.getName().getString());
                world.getServer().runOnServer(server -> {
                    for (BlockPos pos : BlockPos.betweenClosed(at.offset(-3, -1, -3), at.offset(3, 3, 3))) {
                        server.overworld().setBlock(pos, pos.getY() == at.getY() - 1 ? Blocks.STONE.defaultBlockState()
                                : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                });
                world.getServer().runCommand("gamemode spectator " + player);
                world.getServer().runCommand("tp " + player + " " + (at.getX() + 0.5) + " " + (at.getY() + 4)
                        + " " + (at.getZ() + 6.5) + " 180 25");
                command(context, "zone 1 " + position(at.offset(-1, -1, 0)));
                command(context, "zone 2 " + position(at.offset(1, -1, 0)));
                command(context, "build a three-block marker");
                context.waitFor(mc -> ClientState.preview() != null && FloorPreview.blocks().size() == 3);

                // Default exceeds the old timeout; shorten only when rerunning the other lifecycle checks.
                Instant deadline = Instant.now().plusSeconds(Integer.getInteger("justbuild.testPreviewWaitSeconds", 125));
                context.runOnClient(mc -> mc.setScreen(new PauseScreen(true)));
                context.waitTicks(100);
                check(context.computeOnClient(mc -> mc.isPaused()), "the preview pause test did not pause the game");
                context.runOnClient(mc -> mc.setScreen(null));
                context.waitTicks(30);
                check(context.computeOnClient(mc -> FloorPreview.blocks().size() == 3), "pausing lost the ghost model");
                while (Instant.now().isBefore(deadline)) {
                    context.waitTicks(20);
                    check(context.computeOnClient(mc -> ClientState.preview() != null), "the waiting preview expired");
                }
                context.takeScreenshot("preview-after-two-minutes-and-pause");
                check(world.getServer().computeOnServer(server -> server.overworld().getBlockState(at.west()).isAir()),
                        "the waiting preview placed blocks without confirmation");

                // Another player can put their belongings here while the preview is waiting.
                world.getServer().runOnServer(server -> {
                    server.overworld().setBlock(at, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
                    ((ChestBlockEntity) server.overworld().getBlockEntity(at)).setItem(0, new ItemStack(Items.DIAMOND, 5));
                });
                int previousLimit = config.maxBlocks;
                config.maxBlocks = 2;
                store.save(config);
                command(context, "reload");
                command(context, "confirm");
                check(context.computeOnClient(mc -> ClientState.preview() != null), "changed limits discarded the preview");
                check(world.getServer().computeOnServer(server -> server.overworld().getBlockState(at.west()).isAir()),
                        "confirmation ignored the current block limit");
                config.maxBlocks = previousLimit;
                store.save(config);
                command(context, "reload");
                command(context, "confirm");
                context.waitFor(mc -> ClientState.preview() == null);
                context.waitTicks(20);
                check(world.getServer().computeOnServer(server -> server.overworld().getBlockState(at.west()).is(Blocks.GOLD_BLOCK)
                        && server.overworld().getBlockState(at.east()).is(Blocks.GOLD_BLOCK)), "the retained preview did not build");
                check(world.getServer().computeOnServer(server -> server.overworld().getBlockEntity(at) instanceof ChestBlockEntity chest
                        && chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 5), "confirmation destroyed new belongings");
                check(calls.get() == 1, "waiting or retrying confirmation called the AI again");
                check(context.computeOnClient(mc -> FloorPreview.blocks().isEmpty()), "confirmation left a ghost model behind");

                command(context, "select clear");
                command(context, "build another marker to cancel");
                context.waitFor(mc -> ClientState.preview() != null);
                command(context, "cancel");
                check(context.computeOnClient(mc -> ClientState.preview() == null && FloorPreview.blocks().isEmpty()),
                        "Cancel did not clear the preview and its ghost model");
                command(context, "build another marker before leaving");
                context.waitFor(mc -> ClientState.preview() != null);
            }
            // Closing the integrated server can finish before the client receives its disconnect event.
            context.waitFor(mc -> ClientState.preview() == null && FloorPreview.blocks().isEmpty(), 100);
        } catch (Exception e) {
            throw new AssertionError("Could not exercise persistent previews", e);
        } finally {
            stub.stop(0);
            try {
                if (original == null) Files.deleteIfExists(file);
                else Files.write(file, original);
            } catch (Exception e) { throw new AssertionError("Could not restore test config", e); }
        }
    }

    private static HttpServer stub(AtomicInteger calls) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                calls.incrementAndGet();
                JsonObject message = new JsonObject(); message.addProperty("content", PLAN);
                JsonObject choice = new JsonObject(); choice.add("message", message);
                JsonArray choices = new JsonArray(); choices.add(choice);
                JsonObject reply = new JsonObject(); reply.add("choices", choices);
                byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) { throw new AssertionError("Could not start test AI", e); }
    }

    private static String position(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }
    private static void command(ClientGameTestContext context, String text) {
        context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + text));
        context.waitTicks(10);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
