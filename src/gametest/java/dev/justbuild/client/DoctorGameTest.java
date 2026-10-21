package dev.justbuild.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.config.ConfigStore;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.net.DoctorPayload;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;

/** Real networked Doctor reports: permissions, streaming, long lists, input, copy, and stale replies. */
public final class DoctorGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var file = FabricLoader.getInstance().getConfigDir().resolve("justbuild.json");
        byte[] before = null;
        HttpServer stub = null;
        var release = new CountDownLatch(1);
        var requests = new AtomicInteger();
        String clipboard = context.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
        try {
            before = Files.exists(file) ? Files.readAllBytes(file) : null;
            stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            stub.createContext("/", exchange -> {
                requests.incrementAndGet();
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith("/20/")) try { release.await(30, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                boolean fail = path.startsWith("/02/");
                byte[] bytes = (fail ? "{\"error\":{\"message\":\"Fixture login needs attention\"}}"
                        : "{\"data\":[{\"id\":\"fixture-model\"}]}").getBytes(StandardCharsets.UTF_8);
                try { exchange.sendResponseHeaders(fail ? 401 : 200, bytes.length); exchange.getResponseBody().write(bytes); }
                finally { exchange.close(); }
            });
            stub.start();
            var profiles = new ArrayList<JustBuildConfig.ModelProfile>();
            for (int i = 1; i <= JustBuildConfig.MAX_PROFILES; i++) {
                String number = "%02d".formatted(i);
                var profile = new JustBuildConfig.ModelProfile("Provider " + number, "openai", "fixture-model");
                profile.baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/" + number + "/v1";
                profiles.add(profile);
            }
            var config = JustBuildConfig.defaults(); config.replaceProfiles(profiles, profiles.getFirst().name);
            new ConfigStore(file).save(config);
            try (var server = context.worldBuilder().createServer(); var connection = server.connect()) {
                connection.waitForChunksRender();
                context.runOnClient(mc -> mc.gui.setScreen(new JustBuildScreen()));
                context.clickScreenButton("Settings");
                context.clickScreenButton("Doctor");
                context.waitForScreen(DoctorScreen.class);
                context.waitFor(mc -> report(mc.gui.screen()).getMessage().getString().contains("Only an operator"));
                check(requests.get() == 0, "a non-operator invoked server providers");
                context.takeScreenshot("doctor-permission");
                context.clickScreenButton("Close");
                String name = context.computeOnClient(mc -> mc.player.getName().getString());
                server.runCommand("op " + name);
                context.waitTicks(10);
                context.clickScreenButton("Doctor");
                context.waitForScreen(DoctorScreen.class);
                context.waitFor(mc -> report(mc.gui.screen()).getMessage().getString().contains("Provider 19"), 400);
                context.runOnClient(mc -> {
                    String text = report(mc.gui.screen()).getMessage().getString();
                    check(text.contains("Provider 01") && text.contains("Needs attention · Provider 02"), "missing success or error result");
                    check(!text.contains("Provider 20"), "delayed result arrived before release");
                    check(report(mc.gui.screen()).showingScrollBar(), "20 profiles did not produce a scrollable report");
                    for (var child : mc.gui.screen().children()) if (child instanceof AbstractWidget widget)
                        check(widget.getX() >= 0 && widget.getY() >= 0 && widget.getRight() <= mc.gui.screen().width
                                && widget.getBottom() <= mc.gui.screen().height, "dialog control left the screen");
                });
                context.getInput().setCursorPos(0, 0);
                context.runOnClient(mc -> mc.gui.toastManager().clear());
                context.takeScreenshot("doctor-results-progress");
                double[] center = context.computeOnClient(mc -> {
                    var body = report(mc.gui.screen()); int scale = mc.getWindow().getGuiScale();
                    return new double[] {(body.getX() + body.getWidth() / 2.0) * scale, (body.getY() + body.getHeight() / 2.0) * scale};
                });
                context.getInput().setCursorPos(center[0], center[1]);
                context.getInput().scroll(-4);
                context.waitTicks(2);
                check(context.computeOnClient(mc -> report(mc.gui.screen()).scrollAmount() > 0), "mouse wheel did not scroll results");
                context.getInput().pressKey(InputConstants.KEY_HOME);
                check(context.computeOnClient(mc -> report(mc.gui.screen()).scrollAmount() == 0), "Home did not reach the first result");
                context.getInput().pressKey(InputConstants.KEY_PAGEDOWN);
                check(context.computeOnClient(mc -> report(mc.gui.screen()).scrollAmount() > 0), "Page Down did not scroll results");
                long oldId = context.computeOnClient(mc -> requestId(mc.gui.screen()));
                context.clickScreenButton("Close");
                context.waitForScreen(JustBuildScreen.class);
                context.runOnClient(mc -> DoctorScreen.receive(new DoctorPayload(oldId, 0, 20, "Stale report", false)));
                context.waitForScreen(JustBuildScreen.class);
                context.clickScreenButton("Doctor");
                context.waitForScreen(DoctorScreen.class);
                context.runOnClient(mc -> {
                    DoctorScreen.receive(new DoctorPayload(oldId, -1, 0, "Stale report", true));
                    check(!report(mc.gui.screen()).getMessage().getString().contains("Stale report"), "old result entered the new dialog");
                });
                context.waitFor(mc -> report(mc.gui.screen()).getMessage().getString().contains("Provider 01"), 400);
                server.runCommand("deop " + name);
                release.countDown();
                context.waitFor(mc -> report(mc.gui.screen()).getMessage().getString().contains("no longer have permission"), 400);
                context.clickScreenButton("Close");
                server.runCommand("op " + name);
                context.clickScreenButton("Doctor");
                context.waitFor(mc -> {
                    String text = report(mc.gui.screen()).getMessage().getString();
                    return java.util.stream.IntStream.rangeClosed(1, 20).allMatch(i -> text.contains("Provider %02d".formatted(i)));
                }, 400);
                context.clickScreenButton("Copy results");
                context.runOnClient(mc -> {
                    String copied = mc.keyboardHandler.getClipboard();
                    for (int i = 1; i <= 20; i++) check(copied.contains("Provider %02d".formatted(i)), "copy omitted provider " + i);
                    mc.gui.screen().setFocused(report(mc.gui.screen()));
                });
                context.getInput().pressKey(InputConstants.KEY_END);
                check(context.computeOnClient(mc -> report(mc.gui.screen()).scrollAmount() == report(mc.gui.screen()).maxScrollAmount()),
                        "End did not reach the last result");
                context.getInput().setCursorPos(0, 0);
                context.takeScreenshot("doctor-results-bottom");
                context.getInput().resizeWindow(640, 480);
                context.waitTicks(5);
                context.getInput().pressKey(InputConstants.KEY_END);
                context.runOnClient(mc -> {
                    check(report(mc.gui.screen()).scrollAmount() == report(mc.gui.screen()).maxScrollAmount(),
                            "resized dialog could not reach the last result");
                    for (var child : mc.gui.screen().children()) if (child instanceof AbstractWidget widget)
                        check(widget.getRight() <= mc.gui.screen().width && widget.getBottom() <= mc.gui.screen().height,
                                "resized dialog control left the screen");
                });
                context.takeScreenshot("doctor-results-small");
                context.getInput().pressKey(InputConstants.KEY_ESCAPE);
                context.waitForScreen(JustBuildScreen.class);
                context.runOnClient(mc -> mc.gui.setScreen(null));
                // The command path continues to write to chat without opening a dialog.
                long mark = context.computeOnClient(mc -> ClientState.lineCount());
                context.runOnClient(mc -> mc.player.connection.sendCommand("jb doctor"));
                context.waitFor(mc -> ClientState.lineCount() >= mark + 21, 400);
                check(context.computeOnClient(mc -> mc.gui.screen() == null), "chat command unexpectedly opened a modal");
            }
        } catch (Exception e) { throw new AssertionError(e); }
        finally {
            release.countDown(); if (stub != null) stub.stop(0);
            context.runOnClient(mc -> ClientClipboard.set(clipboard));
            try { if (before == null) Files.deleteIfExists(file); else Files.write(file, before); }
            catch (Exception e) { throw new AssertionError(e); }
        }
    }
    private static FittingMultiLineTextWidget report(Screen screen) {
        return screen.children().stream().filter(FittingMultiLineTextWidget.class::isInstance)
                .map(FittingMultiLineTextWidget.class::cast).findFirst().orElseThrow();
    }
    private static long requestId(Screen screen) {
        try { var field = DoctorScreen.class.getDeclaredField("id"); field.setAccessible(true); return field.getLong(screen); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
