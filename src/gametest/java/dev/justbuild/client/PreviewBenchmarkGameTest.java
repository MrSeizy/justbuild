package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.justbuild.net.GhostPreviewPayload;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

/** Opt-in CPU submission/receive timings and observed frame intervals; no hard FPS assertion across machines. */
public final class PreviewBenchmarkGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        String label = System.getenv("JUSTBUILD_PREVIEW_BENCHMARK");
        if (label == null || !label.matches("[a-z0-9-]{1,40}")) return;
        JsonObject report = new JsonObject(); report.addProperty("label", label); report.addProperty("java", System.getProperty("java.version"));
        report.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        report.addProperty("recorded", java.time.Instant.now().toString()); JsonArray cases = new JsonArray(); report.add("cases", cases);
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            String player = context.computeOnClient(mc -> mc.player.getName().getString());
            BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
            String dimension = context.computeOnClient(mc -> mc.level.dimension().identifier().toString());
            world.getServer().runCommand("gamemode spectator " + player);
            for (int size : List.of(50_000, 100_000, 250_000)) {
                List<GhostPreviewPayload.Cell> cells = new ArrayList<>(size);
                for (int i = 0; i < size; i++) cells.add(new GhostPreviewPayload.Cell(at.offset(-48 + i % 96, i / 9216, i / 96 % 96), i % 2, GhostPreviewPayload.ADD));
                var payload = new GhostPreviewPayload(dimension, List.of("minecraft:stone", "minecraft:oak_planks"), cells, size);
                List<Long> receives = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    long nanos = context.computeOnClient(mc -> { long start = System.nanoTime(); GhostPreview.receive(payload); return System.nanoTime() - start; });
                    if (i > 0) receives.add(nanos); context.waitTicks(2);
                }
                for (boolean facing : List.of(true, false)) {
                    world.getServer().runCommand("tp " + player + " " + (at.getX() + 0.5) + " " + (at.getY() + 40) + " " + (at.getZ() - 20.5)
                            + (facing ? " 0 35" : " 180 0"));
                    context.waitTicks(20);
                    List<Long> renders = new ArrayList<>(), intervals = new ArrayList<>(); long[] previous = {0};
                    context.runOnClient(mc -> GhostPreview.renderNanos = n -> {
                        if (renders.size() < 2000) {
                            renders.add(n); long now = System.nanoTime(); if (previous[0] != 0) intervals.add(now - previous[0]); previous[0] = now;
                        }
                    });
                    context.waitTicks(50);
                    context.runOnClient(mc -> GhostPreview.renderNanos = null);
                    if (renders.size() < 10) throw new AssertionError("Too few preview frames for a benchmark: " + renders.size());
                    JsonObject row = new JsonObject(); row.addProperty("cells", size); row.addProperty("view", facing ? "visible" : "looking-away");
                    row.addProperty("frames", renders.size()); row.addProperty("receiveMedianMs", ms(receives, .5));
                    row.addProperty("submitMedianMs", ms(renders, .5)); row.addProperty("submitP95Ms", ms(renders, .95));
                    row.addProperty("frameMedianMs", ms(intervals, .5)); row.addProperty("frameP95Ms", ms(intervals, .95)); cases.add(row);
                }
            }
            context.runOnClient(mc -> GhostPreview.clear());
            var path = FabricLoader.getInstance().getGameDir().resolve("preview-benchmark-" + label + ".json");
            Files.writeString(path, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
        } catch (Exception e) { throw new AssertionError(e); }
        finally { context.runOnClient(mc -> { GhostPreview.renderNanos = null; GhostPreview.clear(); }); }
    }
    private static double ms(List<Long> values, double percentile) {
        var sorted = values.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size() - 1, (int) ((sorted.size() - 1) * percentile))) / 1_000_000.0;
    }
}
