package dev.justbuild.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Real chat routing and clickable help navigation, including the bare command and invalid queries. */
public final class CommandHelpGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext c) {
        try {
            new dev.justbuild.config.ConfigStore(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("justbuild.json")).save(dev.justbuild.config.JustBuildConfig.defaults());
        } catch (dev.justbuild.config.ConfigException e) { throw new AssertionError("Could not prepare command help test", e); }
        try (TestSingleplayerContext w = c.worldBuilder().create()) {
            w.getClientLevel().waitForChunksRender();
            c.runOnClient(mc -> mc.setScreen(new ChatScreen("", false)));
            Component first = command(c, "justbuild", "Commands · all · page 1/");
            check(first.getString().contains("/justbuild new") && first.getString().contains("/justbuild build <description>"),
                    "help did not explain how to start a new build");
            c.takeScreenshot("command-help-first-page");
            String next = commands(first).stream().filter(s -> s.equals("/justbuild help 2")).findFirst().orElseThrow();
            command(c, next.substring(1), "Commands · all · page 2/");
            Component zone = command(c, "justbuild help zone", "Commands · zone · page 1/");
            check(zone.getString().contains("/justbuild zone wand"), "zone topic omitted the wand");
            c.takeScreenshot("command-help-zone");
            check(commands(zone).contains("/justbuild help zone 2"), "topic pagination lost the topic");
            command(c, "justbuild help zone 2", "/justbuild zone adjust height auto");
            command(c, "justbuild help topics", "[new]");
            c.takeScreenshot("command-help-topics");
            command(c, "justbuild help no-such-command", "Unknown help topic");
            command(c, "justbuild help 0", "has pages 1–");
            command(c, "justbuild new", "New build.");
            command(c, "justbuild profile", "Profiles (edit them in the panel");
            command(c, "justbuild profiles", "Profiles (edit them in the panel");
            // Claude's model list is built in, so this verifies chat routing without a network call.
            command(c, "justbuild profile Claude", "Using profile “Claude”");
            command(c, "justbuild model", "models for “Claude”");
            command(c, "justbuild models", "models for “Claude”");
            command(c, "justbuild model default", "set to CLI default");
            command(c, "justbuild help model", "/justbuild model [name|default]");
            c.takeScreenshot("command-help-model");
            check(c.computeOnClient(mc -> ClientState.preview() == null && ClientState.string("selectedBuild", null) == null),
                    "help or new unexpectedly selected a build or started a preview");
        }
    }

    private static Component command(ClientGameTestContext c, String command, String expected) {
        long mark = c.computeOnClient(mc -> ClientState.lineCount());
        c.runOnClient(mc -> {
            mc.gui.getChat().clearMessages(false);
            mc.player.connection.sendCommand(command);
        });
        c.waitFor(mc -> ClientState.lastLineSince(mark) != null
                && ClientState.lastLineSince(mark).getString().contains(expected));
        Component line = c.computeOnClient(mc -> ClientState.lastLineSince(mark));
        check(line.getString().contains(expected), command + " returned: " + line.getString());
        c.waitTicks(2);
        return line;
    }

    private static List<String> commands(Component message) {
        List<String> commands = new ArrayList<>();
        message.visit((style, text) -> {
            if (style.getClickEvent() instanceof ClickEvent.RunCommand run) commands.add(run.command());
            return Optional.<Void>empty();
        }, Style.EMPTY);
        return commands;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
