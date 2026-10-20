package dev.justbuild.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.JustBuildMod;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.PlanRotation;
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
import net.minecraft.core.Direction;

/**
 * Builds a small house with a gable roof (a stub endpoint plays the AI) and photographs the gable end from two angles,
 * so roof changes can be judged by eye.
 */
public final class RoofTest implements FabricClientGameTest {
    static final String PLAN = """
            {"kind":"build","question":null,"options":null,"title":"Roof Test House","summary":"A stone house.",
             "operations":[
               {"op":"fill","block":"cobblestone","from":[-3,-1,-11],"to":[3,-1,-3],"radius":null,"height":null,"hollow":null},
               {"op":"fill","block":"stone_bricks","from":[-3,0,-11],"to":[3,4,-3],"radius":null,"height":null,"hollow":true},
               {"op":"fill","block":"air","from":[0,0,-3],"to":[0,1,-3],"radius":null,"height":null,"hollow":null},
               {"op":"set","block":"glass_pane","from":[-2,2,-3],"to":null,"radius":null,"height":null,"hollow":null},
               {"op":"set","block":"glass_pane","from":[2,2,-3],"to":null,"radius":null,"height":null,"hollow":null},
               {"op":"roof","block":"spruce_stairs","trim":"oak_planks","from":[-4,5,-12],"to":[4,5,-2],"style":"gable","axis":null},
               {"op":"fill","block":"cobblestone","from":[10,-1,-11],"to":[16,-1,-3],"radius":null,"height":null,"hollow":null},
               {"op":"fill","block":"spruce_planks","from":[10,0,-11],"to":[16,4,-3],"radius":null,"height":null,"hollow":true},
               {"op":"roof","block":"granite_stairs","trim":"birch_planks","edge":"dark_oak_stairs","pitch":2,"from":[9,5,-12],"to":[17,5,-2],"style":"gable","axis":null},
               {"op":"line","block":"stripped_dark_oak_log","from":[13,5,-3],"to":[13,11,-3]},
               {"op":"line","block":"stripped_dark_oak_log","from":[11,7,-3],"to":[15,7,-3]},
               {"op":"fill","block":"white_concrete","from":[-16,0,-11],"to":[-10,4,-3],"radius":null,"height":null,"hollow":true},
               {"op":"fill","block":"glass","from":[-15,1,-3],"to":[-11,3,-3],"radius":null,"height":null,"hollow":null},
               {"op":"roof","block":"smooth_quartz_stairs","trim":"white_concrete","from":[-17,5,-12],"to":[-9,5,-2],"style":"gable","axis":null,"rim":false,"eaves":false}]}
            """;

    @Override
    public void runTest(ClientGameTestContext context) {
        HttpServer stub = startStub();
        ConfigStore store = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("justbuild.json"));
        write(store, stub.getAddress().getPort());
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            double[] at = context.computeOnClient(mc -> new double[] {mc.player.getX(), mc.player.getY(),
                    mc.player.getZ(), mc.player.getYRot()});
            String name = context.computeOnClient(mc -> mc.player.getName().getString());
            Direction facing = Direction.fromYRot(at[3]);
            BlockPos origin = BlockPos.containing(at[0], at[1], at[2]).relative(facing, 2);
            PlanRotation rotation = PlanRotation.forFacing(facing.getName());

            context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild a small stone house"));
            for (int i = 0; i < 60 && built(singleplayer) == 0; i++) {
                context.waitTicks(10);
            }
            check(built(singleplayer) == 1, "the house was not built");
            context.waitTicks(40);

            look(context, singleplayer, name, origin, rotation, new Cell(7, 6, 4), new Cell(0, 7, -2));
            context.takeScreenshot("roof-gable-front");
            look(context, singleplayer, name, origin, rotation, new Cell(-3, 4, 1), new Cell(0, 8, -2));
            context.takeScreenshot("roof-gable-close");
            // The second house: granite roof, dark oak edge, steep pitch, birch triangle with a timber frame.
            look(context, singleplayer, name, origin, rotation, new Cell(21, 7, 5), new Cell(13, 8, -3));
            context.takeScreenshot("roof-steep-edged");
            // The third: a modern gable with "rim" and "eaves" off.
            look(context, singleplayer, name, origin, rotation, new Cell(-6, 6, 5), new Cell(-13, 7, -3));
            context.takeScreenshot("roof-plain");
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

    /** Moves the player to {@code eye} and points them at {@code target}, both in the plan frame. */
    private static void look(ClientGameTestContext context, TestSingleplayerContext singleplayer, String name,
            BlockPos origin, PlanRotation rotation, Cell eye, Cell target) {
        BlockPos e = origin.offset(rotation.apply(eye).x(), rotation.apply(eye).y(), rotation.apply(eye).z());
        BlockPos t = origin.offset(rotation.apply(target).x(), rotation.apply(target).y(), rotation.apply(target).z());
        singleplayer.getServer().runCommand("tp " + name + " " + (e.getX() + 0.5) + " " + e.getY() + " "
                + (e.getZ() + 0.5) + " facing " + (t.getX() + 0.5) + " " + (t.getY() + 0.5) + " " + (t.getZ() + 0.5));
        context.waitTicks(20);
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

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
