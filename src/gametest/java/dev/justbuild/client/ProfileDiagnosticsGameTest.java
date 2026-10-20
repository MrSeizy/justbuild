package dev.justbuild.client;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import dev.justbuild.net.ProfileTestPayload;
import dev.justbuild.provider.ProfileDiagnostics;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.EditBox;

/** Real draft editor, server/local ownership, cancellation and response identity with a loopback-only provider. */
public final class ProfileDiagnosticsGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var file = FabricLoader.getInstance().getConfigDir().resolve("justbuild.json");
        byte[] before = read(file); HttpServer stub = null;
        var models = new CopyOnWriteArrayList<String>(); var auth = new CopyOnWriteArrayList<String>();
        var block = new AtomicBoolean(); var release = new CountDownLatch(1);
        try {
            stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            stub.createContext("/v1/models", x -> {
                byte[] bytes = "{\"data\":[{\"id\":\"unsaved-model\"}]}".getBytes(StandardCharsets.UTF_8);
                x.sendResponseHeaders(200, bytes.length); x.getResponseBody().write(bytes); x.close();
            });
            stub.createContext("/v1/chat/completions", x -> {
                models.add(JsonParser.parseString(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().get("model").getAsString());
                auth.add(x.getRequestHeaders().getFirst("Authorization"));
                if (block.get()) try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}".getBytes(StandardCharsets.UTF_8);
                try { x.sendResponseHeaders(200, bytes.length); x.getResponseBody().write(bytes); } finally { x.close(); }
            }); stub.start();
            var cfg = JustBuildConfig.defaults(); var p = new JustBuildConfig.ModelProfile("Saved", "openai", "saved-model");
            p.baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1"; p.apiKey = "sk-diagnostic-fixture";
            cfg.replaceProfiles(List.of(p), p.name); new ConfigStore(file).save(cfg); byte[] saved = read(file);
            try (var world = context.worldBuilder().create()) {
                context.waitFor(mc -> !ClientState.profiles().isEmpty());
                context.runOnClient(mc -> {
                    mc.setScreen(new ProfilesScreen(new JustBuildScreen(), false));
                    field(mc.screen, "Model (required)").setValue("unsaved-model");
                });
                context.clickScreenButton("Test connection");
                context.waitFor(mc -> result(mc.screen) != null, 500);
                check(models.equals(List.of("unsaved-model")), "server tested saved model instead of draft");
                check(auth.equals(List.of("Bearer sk-diagnostic-fixture")), "server did not resolve saved key");
                check(java.util.Arrays.equals(saved, read(file)), "test saved profile edits");
                context.runOnClient(mc -> { check(result(mc.screen).stream().allMatch(c -> c.status() == ProfileDiagnostics.Status.PASS), "connection test failed"); mc.screen.resize(320, 240); });
                context.takeScreenshot("profile-connection-results");
                context.clickScreenButton("Previous check"); context.clickScreenButton("Previous check");
                context.clickScreenButton("Back to profiles");
                context.runOnClient(mc -> check(field(mc.screen, "Model (required)").getValue().equals("unsaved-model"), "returning erased draft"));
                block.set(true);
                context.clickScreenButton("Test connection");
                context.waitFor(mc -> models.size() == 2, 400);
                context.runOnClient(mc -> {
                    var screen = mc.screen;
                    ProfileTestScreen.receive(new ProfileTestPayload(id(screen) + 1, List.of(ProfileDiagnostics.unexpected())));
                    check(result(screen) == null, "stale response accepted");
                });
                context.clickScreenButton("Cancel test");
                context.runOnClient(mc -> {
                    mc.setScreen(new ProfilesScreen(new JustBuildScreen(), true));
                    field(mc.screen, "Model (required)").setValue("local-unsaved");
                });
                context.clickScreenButton("Test connection");
                context.waitFor(mc -> models.size() == 3, 400);
                context.runOnClient(mc -> {
                    ProfileTestScreen.receive(new ProfileTestPayload(id(mc.screen), List.of(ProfileDiagnostics.unexpected())));
                    check(result(mc.screen) == null, "server reply entered local test");
                });
                release.countDown();
                context.waitFor(mc -> result(mc.screen) != null, 400);
                check(models.getLast().equals("local-unsaved"), "local test ignored draft");
                check(java.util.Arrays.equals(saved, read(file)), "local test saved draft");
                context.clickScreenButton("Back to profiles"); context.runOnClient(mc -> mc.setScreen(null));
            }
        } catch (Exception e) { throw new AssertionError(e); }
        finally { release.countDown(); if (stub != null) stub.stop(0); restore(file, before); }
    }
    private static EditBox field(net.minecraft.client.gui.screens.Screen s, String hint) { return s.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).filter(e -> e.getMessage().getString().equals(hint)).findFirst().orElseThrow(); }
    @SuppressWarnings("unchecked") private static List<ProfileDiagnostics.Check> result(Object s) { if (!(s instanceof ProfileTestScreen)) return null; return (List<ProfileDiagnostics.Check>) value(s, "checks"); }
    private static long id(Object s) { return (long) value(s, "id"); }
    private static Object value(Object s, String name) { try { var f = s.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(s); } catch (Exception e) { throw new AssertionError(e); } }
    private static byte[] read(java.nio.file.Path p) { try { return Files.exists(p) ? Files.readAllBytes(p) : null; } catch (Exception e) { throw new AssertionError(e); } }
    private static void restore(java.nio.file.Path p, byte[] b) { try { if (b == null) Files.deleteIfExists(p); else Files.write(p, b); } catch (Exception e) { throw new AssertionError(e); } }
    private static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }
}
