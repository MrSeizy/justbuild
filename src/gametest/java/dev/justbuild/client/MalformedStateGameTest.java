package dev.justbuild.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Malformed panel state from a server is skipped, never thrown while a screen draws it: Minecraft turns an exception
 * while rendering a screen into a crash.
 */
public final class MalformedStateGameTest implements FabricClientGameTest {
    private static final String STATE = "{\"provider\":\"x\",\"selectedBuild\":\"0123abcd\","
            + "\"activity\":{\"question\":\"Which way?\",\"options\":[{},null,\"north\"]},"
            + "\"builds\":[{\"id\":\"0123abcd\"},{\"id\":\"89abcdef\",\"title\":\"Hut\",\"versions\":1,\"request\":\"a hut\","
            + "\"elsewhere\":null}],\"checkpoints\":[{}]}";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            show(context, "CREATE", () -> new JustBuildScreen());
            check(context.computeOnClient(mc -> ClientState.questionOptions()).equals(java.util.List.of("north")),
                    "only the plain option should be kept");
            show(context, "HISTORY", () -> new JustBuildScreen());
            check(context.computeOnClient(mc -> ClientState.builds().size()) == 1, "only the complete build should be kept");
            show(context, "HISTORY", () -> new BuildDetailsScreen(null, "0123abcd"));
            context.runOnClient(mc -> {
                ClientState.update(STATE);
                var builds = ClientState.builds(); var options = ClientState.questionOptions();
                check(builds == ClientState.builds() && options == ClientState.questionOptions(), "state was decoded again during access");
                immutable(() -> builds.clear()); immutable(() -> options.add("south")); immutable(() -> ClientState.list("checkpoints").add("bad"));
                ClientState.set("model", "unsaved-model");
                check(builds == ClientState.builds() && ClientState.string("model", "").equals("unsaved-model"), "optimistic settings rebuilt or lost structured state");
                ClientState.update("{"); check(ClientState.builds() == builds, "bad JSON replaced good state");
                ClientState.update("{\"provider\":\"new\",\"workshop\":{\"route\":{\"remainingMillis\":{}}}}");
                check(ClientState.string("provider", "").equals("new") && ClientState.route() == null, "malformed optional route prevented the new state");
                ClientState.update(STATE);
                check(ClientState.builds() != builds && ClientState.builds().equals(builds), "new documents did not install a fresh snapshot");
                mc.gui.setScreen(null);
            });
        }
    }

    /** Injects the malformed state, opens the screen on a tab, and lets it draw a few frames. */
    private static void show(ClientGameTestContext context, String tab, java.util.function.Supplier<net.minecraft.client.gui.screens.Screen> screen) {
        context.runOnClient(mc -> {
            ClientState.update(STATE);
            selectTab(tab);
            mc.gui.setScreen(screen.get());
        });
        context.waitTicks(5);
        // The server's own state could replace the injected one; the check would then pass without testing anything.
        check(context.computeOnClient(mc -> "Which way?".equals(ClientState.question())), "the injected state was replaced");
    }

    private static void selectTab(String name) {
        try {
            var field = JustBuildScreen.class.getDeclaredField("tab");
            field.setAccessible(true);
            for (Object value : field.getType().getEnumConstants()) {
                if (((Enum<?>) value).name().equals(name)) field.set(null, value);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not select the " + name + " tab", e);
        }
    }

    private static void immutable(Runnable mutation) {
        try { mutation.run(); throw new AssertionError("A decoded collection was mutable"); }
        catch (UnsupportedOperationException expected) { }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
