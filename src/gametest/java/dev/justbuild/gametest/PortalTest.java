package dev.justbuild.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.JustBuildMod;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;

/**
 * Portals built by the portal operation must still be working blocks after placement and the shape refresh: a
 * decorated nether portal (crying obsidian written after it, which must not break it) and an end portal, built with
 * the player facing east so the plan is rotated.
 */
public final class PortalTest implements FabricClientGameTest {
    static final String PLAN = """
            {"kind":"build","question":null,"options":null,"title":"Portal Test","summary":"Two portals.",
             "operations":[
               {"op":"portal","style":"nether","from":[-1,1,-6],"to":[1,4,-6],"hollow":false},
               {"op":"fill","block":"crying_obsidian","from":[-3,0,-6],"to":[3,6,-6],"hollow":false},
               {"op":"fill","block":"blackstone","from":[-3,-1,-8],"to":[3,-1,-4],"hollow":false},
               {"op":"portal","style":"end","from":[0,0,-14],"to":null,"hollow":false},
               {"op":"fill","block":"stone_bricks","from":[-3,-1,-17],"to":[3,-1,-11],"hollow":false}]}
            """;

    @Override
    public void runTest(ClientGameTestContext context) {
        HttpServer stub = startStub();
        ConfigStore store = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("justbuild.json"));
        write(store, stub.getAddress().getPort());
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            String name = context.computeOnClient(mc -> mc.player.getName().getString());
            int[] at = context.computeOnClient(mc -> new int[] {mc.player.getBlockX(), mc.player.getBlockY(),
                    mc.player.getBlockZ()});
            // Face east, level, so the plan is turned a quarter and the crosshair points at nothing.
            singleplayer.getServer().runCommand("tp " + name + " " + (at[0] + 0.5) + " " + at[1] + " " + (at[2] + 0.5)
                    + " -90 0");
            context.waitTicks(10);
            context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild two portals"));
            for (int i = 0; i < 60 && built(singleplayer) == 0; i++) {
                context.waitTicks(10);
            }
            check(built(singleplayer) == 1, "the portals were not built");
            context.waitTicks(60);
            int[] counts = singleplayer.getServer().computeOnServer(server -> {
                var level = server.overworld();
                int nether = 0, end = 0, frames = 0, eyes = 0;
                for (BlockPos pos : BlockPos.betweenClosed(at[0] - 2, at[1] - 3, at[2] - 12, at[0] + 24, at[1] + 10,
                        at[2] + 12)) {
                    var state = level.getBlockState(pos);
                    if (state.is(Blocks.NETHER_PORTAL)) {
                        nether++;
                    } else if (state.is(Blocks.END_PORTAL)) {
                        end++;
                    } else if (state.is(Blocks.END_PORTAL_FRAME)) {
                        frames++;
                        if (state.getValue(EndPortalFrameBlock.HAS_EYE)) {
                            eyes++;
                        }
                    }
                }
                return new int[] {nether, end, frames, eyes};
            });
            System.out.println("[portals] nether " + counts[0] + ", end " + counts[1] + ", frames " + counts[2]
                    + ", eyes " + counts[3]);
            check(counts[0] == 12, "expected the 3 x 4 nether portal to stay lit, found " + counts[0] + " portal blocks");
            check(counts[1] == 9, "expected a 3 x 3 end portal, found " + counts[1]);
            check(counts[2] == 12 && counts[3] == 12, "expected 12 end portal frames with eyes, found " + counts[2]
                    + " frames and " + counts[3] + " eyes");
            singleplayer.getServer().runCommand("tp " + name + " " + (at[0] - 4.5) + " " + (at[1] + 3) + " "
                    + (at[2] - 4.5) + " facing " + (at[0] + 8) + " " + (at[1] + 2) + " " + (at[2] + 0.5));
            context.runOnClient(mc -> dev.justbuild.client.TestHud.hidden(mc, true));
            context.waitTicks(30);
            context.takeScreenshot("portals");
            context.runOnClient(mc -> dev.justbuild.client.TestHud.hidden(mc, false));
        } finally {
            stub.stop(0);
            try {
                store.save(JustBuildConfig.defaults());
            } catch (Exception e) {
                throw new AssertionError("Could not reset " + store.file(), e);
            }
        }
    }

    private static int built(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(s -> JustBuildMod.manager() == null ? 0
                : JustBuildMod.manager().savedBuildCount());
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    private static HttpServer startStub() {
        try {
            HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                JsonObject message = new JsonObject();
                message.addProperty("role", "assistant");
                message.addProperty("content", PLAN);
                JsonObject choice = new JsonObject();
                choice.add("message", message);
                JsonArray choices = new JsonArray();
                choices.add(choice);
                JsonObject reply = new JsonObject();
                reply.add("choices", choices);
                byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            stub.start();
            return stub;
        } catch (IOException e) {
            throw new AssertionError("Could not start the stub endpoint", e);
        }
    }

    private static void write(ConfigStore store, int port) {
        try {
            JustBuildConfig config = JustBuildConfig.defaults();
            JustBuildConfig.ModelProfile stub = new JustBuildConfig.ModelProfile("Stub", "openai", "stub-model");
            stub.baseUrl = "http://127.0.0.1:" + port + "/v1";
            List<JustBuildConfig.ModelProfile> profiles = new ArrayList<>(config.profiles);
            profiles.add(stub);
            config.replaceProfiles(profiles, "Stub");
            config.previewAbove = -1;
            config.detail = "quick";
            config.creativeDirection = false;
            store.save(config);
        } catch (Exception e) {
            throw new AssertionError("Could not write " + store.file(), e);
        }
    }
}
