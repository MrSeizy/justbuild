package dev.justbuild.gametest;

import dev.justbuild.client.JustBuildScreen;
import dev.justbuild.client.ModelPickerScreen;
import dev.justbuild.client.ProfilesScreen;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarrationThunk;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Opens the panel in a fresh singleplayer world, screenshots each tab, and edits profiles end to end
 * (run with ./gradlew runClientGameTest; screenshots land in build/run/clientGameTest/screenshots).
 */
public final class PanelScreenshotTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ConfigStore store = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("justbuild.json"));
        resetProfiles(store);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            // Press Right Shift for real, so the key mapping itself is tested.
            context.getInput().pressKey(InputConstants.KEY_RSHIFT);
            context.waitForScreen(JustBuildScreen.class);
            context.waitTicks(40);
            context.takeScreenshot("justbuild-create");
            context.clickScreenButton("Templates");
            context.waitTicks(5);
            context.takeScreenshot("justbuild-templates");
            // Typing several letters into the filter must keep the focus while the page rebuilds.
            focusBox(context, "Filter");
            context.getInput().typeChars("cas");
            context.waitTicks(5);
            context.takeScreenshot("justbuild-templates-filtered");
            context.clickScreenButton("Medieval castle");
            context.waitTicks(5);
            context.takeScreenshot("justbuild-template-picked");
            context.clickScreenButton("History");
            context.waitTicks(5);
            context.takeScreenshot("justbuild-history");
            context.clickScreenButton("World");
            context.waitTicks(5);
            context.takeScreenshot("justbuild-world");
            // Pressing the current tab does nothing, but Tab still reaches it and a screen reader hears it is selected.
            String request = context.computeOnClient(mc -> request(mc).getValue());
            check(context.computeOnClient(mc -> widget(mc, "World").isActive()), "the current tab can't be focused or narrated");
            String said = narration(context, "World");
            check(said.contains("Selected: World tab"), "the current tab narrates \"" + said + "\"");
            said = narration(context, "History");
            check(said.contains("History tab") && !said.contains("Selected"), "another tab narrates \"" + said + "\"");
            context.clickScreenButton("World");
            context.waitTicks(3);
            check(context.computeOnClient(mc -> request(mc).getValue().equals(request) && widget(mc, "Equip wand: Zone") != null),
                    "pressing the current tab changed the panel");
            context.clickScreenButton("Settings");
            context.waitTicks(5);
            context.takeScreenshot("justbuild-settings");
            serverAiPolicy(context, store);
            context.clickScreenButton("AI review: One pass");
            context.waitTicks(5);
            check("masterpiece".equals(savedDetail(store)), "Two passes changed the saved detail identifier");
            context.takeScreenshot("justbuild-ai-review-two-passes");
            context.clickScreenButton("AI review: Two passes");
            context.waitTicks(5);
            check("quick".equals(savedDetail(store)), "When needed changed the saved detail identifier");
            context.clickScreenButton("AI review: When needed");
            context.waitTicks(5);
            check("detailed".equals(savedDetail(store)), "One pass changed the saved detail identifier");

            // The profile editor and the model picker (Claude's list is built in, so nothing runs).
            context.clickScreenButton("Manage profiles…");
            context.waitForScreen(ProfilesScreen.class);
            context.waitTicks(5);
            context.takeScreenshot("justbuild-profiles");
            context.clickScreenButton("Grok Build");
            check(context.computeOnClient(mc -> mc.gui.screen().children().stream().anyMatch(c -> c instanceof Button b
                    && b.getMessage().getString().equals("Grok Build CLI"))), "Grok profile did not select its CLI");
            context.takeScreenshot("justbuild-profiles-grok");
            context.clickScreenButton("★ Claude");
            context.clickScreenButton("Pick…");
            context.waitForScreen(ModelPickerScreen.class);
            context.waitTicks(20);
            context.takeScreenshot("justbuild-model-picker");
            context.clickScreenButton("opus");
            context.waitForScreen(ProfilesScreen.class);
            context.clickScreenButton("OpenAI-compatible");
            context.waitTicks(5);
            context.takeScreenshot("justbuild-profiles-openai");
            focusBox(context, "Saved (blank keeps it)");
            context.getInput().typeChars("sk-typed-1234");
            context.waitTicks(5);
            context.takeScreenshot("justbuild-profiles-key-hidden");
            context.clickScreenButton("Show");
            context.waitTicks(5);
            context.takeScreenshot("justbuild-profiles-key-shown");
            context.clickScreenButton("OpenAI");
            context.waitTicks(5);
            context.takeScreenshot("justbuild-profiles-preset");
            // Reasoning cycles from the endpoint's default to none, low, medium, and high.
            context.clickScreenButton("Endpoint default");
            context.waitTicks(5);
            context.takeScreenshot("justbuild-profiles-reasoning");
            context.clickScreenButton("New");
            context.waitTicks(5);
            context.takeScreenshot("justbuild-profiles-new");
            // Deleting the new profile selects the preceding starter; return to OpenAI explicitly.
            context.clickScreenButton("Delete");
            context.clickScreenButton("OpenAI-compatible");
            // An OpenAI-compatible profile needs a model before it can be saved.
            focusBox(context, "Model (required)");
            context.getInput().typeChars("gpt-4o-mini");
            context.clickScreenButton("Save");
            context.waitForScreen(JustBuildScreen.class);
            context.waitTicks(20);
            context.takeScreenshot("justbuild-settings-after-save");
            checkSaved(store);

            // A saved key stays with its URL: picking another preset asks for the key again.
            context.clickScreenButton("Manage profiles…");
            context.waitForScreen(ProfilesScreen.class);
            context.clickScreenButton("OpenAI-compatible");
            context.clickScreenButton("OpenRouter");
            context.waitTicks(5);
            context.takeScreenshot("justbuild-profiles-url-changed");
            // Unsaved changes: the first Cancel only warns, the second discards them.
            context.clickScreenButton("Cancel");
            context.waitTicks(5);
            context.takeScreenshot("justbuild-profiles-unsaved");
            check(context.computeOnClient(mc -> mc.gui.screen() instanceof ProfilesScreen), "Cancel dropped unsaved changes without asking");
            context.clickScreenButton("Cancel");
            context.waitForScreen(JustBuildScreen.class);

            // A button that sends a command; the server's reply must show up in the Create tab.
            context.clickScreenButton("Create");
            context.clickScreenButton("Status");
            context.waitTicks(20);
            context.takeScreenshot("justbuild-after-status");
            // Glass states: a hovered button, keyboard focus, and the panel over a bright sky (no blur behind it).
            hover(context, "Status");
            check(context.computeOnClient(mc -> mc.gui.screen().children().stream().anyMatch(c -> c instanceof AbstractWidget w
                    && w.getMessage().getString().equals("Status") && w.isHovered())), "the Status button did not report hovered");
            context.takeScreenshot("glass-hover");
            context.getInput().pressKey(InputConstants.KEY_TAB);
            context.waitTicks(3);
            check(context.computeOnClient(mc -> mc.gui.screen().getFocused() != null), "Tab did not focus any widget");
            context.takeScreenshot("glass-keyboard-focus");
            context.runOnClient(mc -> mc.gui.setScreen(null));
            singleplayer.getServer().runCommand("time set noon");
            context.runOnClient(mc -> mc.player.setXRot(-75));
            context.getInput().pressKey(InputConstants.KEY_RSHIFT);
            context.waitForScreen(JustBuildScreen.class);
            context.waitTicks(5);
            context.takeScreenshot("glass-over-sky");
            dev.justbuild.client.ResponseReviewChecks.run(context);
        }
    }

    /** Puts the mouse over the button with this label (window pixels are GUI pixels times the GUI scale). */
    private static void hover(ClientGameTestContext context, String label) {
        double[] at = context.computeOnClient(mc -> {
            var button = mc.gui.screen().children().stream()
                    .filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w && w.getMessage().getString().equals(label))
                    .map(c -> (net.minecraft.client.gui.components.AbstractWidget) c)
                    .findFirst().orElseThrow(() -> new AssertionError("No button labelled " + label));
            int scale = mc.getWindow().getGuiScale();
            return new double[] {(button.getX() + button.getWidth() / 2.0) * scale, (button.getY() + button.getHeight() / 2.0) * scale};
        });
        context.getInput().setCursorPos(at[0], at[1]);
        context.waitTicks(3);
    }

    private static AbstractWidget widget(Minecraft mc, String label) {
        return mc.gui.screen().children().stream()
                .filter(c -> c instanceof AbstractWidget w && w.getMessage().getString().equals(label))
                .map(c -> (AbstractWidget) c)
                .findFirst().orElseThrow(() -> new AssertionError("No widget labelled " + label));
    }

    private static EditBox request(Minecraft mc) {
        return (EditBox) widget(mc, "Request");
    }

    /** What a screen reader hears for the widget with this label. */
    private static String narration(ClientGameTestContext context, String label) {
        return context.computeOnClient(mc -> {
            StringBuilder said = new StringBuilder();
            widget(mc, label).updateNarration(new NarrationElementOutput() {
                @Override
                public void add(NarratedElementType type, NarrationThunk<?> contents) {
                    contents.getText(text -> said.append(text).append(". "));
                }

                @Override
                public net.minecraft.client.gui.narration.NarrationTrigger narrationTrigger() {
                    return net.minecraft.client.gui.narration.NarrationTrigger.SYSTEM;
                }

                @Override
                public NarrationElementOutput nest() {
                    return this;
                }
            });
            return said.toString();
        });
    }

    /** Focuses the text box whose label starts with {@code label} (EditBox labels are their hints here). */
    private static void focusBox(ClientGameTestContext context, String label) {
        context.runOnClient(mc -> mc.gui.screen().setFocused(mc.gui.screen().children().stream()
                .filter(c -> c instanceof EditBox box && box.getMessage().getString().startsWith(label))
                .findFirst().orElseThrow(() -> new AssertionError("No text box labelled " + label))));
    }

    /** The run directory survives between runs, so start from the starter profiles with a saved OpenAI key. */
    private static void resetProfiles(ConfigStore store) {
        try {
            JustBuildConfig config = store.load();
            JustBuildConfig defaults = JustBuildConfig.defaults();
            config.replaceProfiles(defaults.profiles, "Claude");
            config.playerAi = defaults.playerAi;
            config.detail = "detailed";
            config.profile("OpenAI-compatible").orElseThrow().apiKey = "sk-gametest-0000";
            store.save(config);
        } catch (Exception e) {
            throw new AssertionError("Could not prepare " + store.file(), e);
        }
    }

    private static void serverAiPolicy(ClientGameTestContext context, ConfigStore store) {
        context.clickScreenButton("Server AI…");
        context.waitFor(mc -> mc.gui.screen() != null && mc.gui.screen().getTitle().getString().equals("Server AI policy"));
        context.runOnClient(mc -> {
            check(widget(mc, "★ Require players' AI").active, "the selected policy can't be focused or narrated");
            mc.gui.screen().resize(320, 240);
            for (var child : mc.gui.screen().children()) if (child instanceof AbstractWidget w) {
                check(w.getX() >= 0 && w.getY() >= 0 && w.getRight() <= 320 && w.getBottom() <= 240,
                        "policy control left the small screen: " + w.getMessage().getString());
            }
            mc.gui.screen().resize(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        });
        String said = narration(context, "★ Require players' AI");
        check(said.contains("Selected:"), "the current policy narrates \"" + said + "\"");
        context.clickScreenButton("★ Require players' AI");
        context.waitTicks(3);
        check(savedPlayerAi(store).equals("require"), "pressing the current policy changed it");
        context.getInput().setCursorPos(0, 0);
        context.waitTicks(3);
        context.takeScreenshot("server-ai-policy-default");
        context.clickScreenButton("Prefer players' AI");
        context.waitFor(mc -> mc.gui.screen().children().stream().anyMatch(c -> c instanceof Button b
                && b.getMessage().getString().equals("★ Prefer players' AI")));
        check(savedPlayerAi(store).equals("prefer"), "the UI policy change did not save");
        context.runOnClient(mc -> mc.player.connection.sendCommand("jb playerai off"));
        context.waitFor(mc -> mc.gui.screen().children().stream().anyMatch(c -> c instanceof Button b
                && b.getMessage().getString().equals("★ Use server AI")));
        check(savedPlayerAi(store).equals("off"), "the alias policy command did not save");
        context.takeScreenshot("server-ai-policy-command-update");
        context.clickScreenButton("Require players' AI");
        context.waitFor(mc -> mc.gui.screen().children().stream().anyMatch(c -> c instanceof Button b
                && b.getMessage().getString().equals("★ Require players' AI")));
        check(savedPlayerAi(store).equals("require"), "the UI could not restore Require");
        context.clickScreenButton("Back");
        context.waitForScreen(JustBuildScreen.class);
    }

    private static String savedPlayerAi(ConfigStore store) {
        try { return store.load().playerAi; }
        catch (Exception e) { throw new AssertionError("Could not read saved player AI policy", e); }
    }

    private static String savedDetail(ConfigStore store) {
        try { return store.load().detail; }
        catch (Exception e) { throw new AssertionError("Could not read saved AI review setting", e); }
    }

    private static void checkSaved(ConfigStore store) {
        JustBuildConfig saved;
        try {
            saved = store.load();
            String disk = java.nio.file.Files.readString(store.file());
            check(!disk.contains("sk-typed-1234") && !disk.contains("\"apiKey\""), "Save wrote a plaintext API key");
            check(disk.contains("encryptedApiKey"), "Save did not encrypt the API key");
        } catch (Exception e) {
            throw new AssertionError("Could not read " + store.file(), e);
        }
        JustBuildConfig.ModelProfile openai = saved.profile("OpenAI-compatible").orElseThrow();
        check("Claude".equals(saved.activeProfile), "Save changed the active profile to " + saved.activeProfile);
        check("sk-typed-1234".equals(openai.apiKey), "typed key was not saved");
        check("https://openrouter.ai/api/v1".equals(openai.baseUrl), "base URL is " + openai.baseUrl);
        check("opus".equals(saved.profile("Claude").orElseThrow().model), "Claude model was not saved");
        check("gpt-4o-mini".equals(openai.model), "model is " + openai.model);
        check("none".equals(openai.reasoningEffort), "reasoning effort is " + openai.reasoningEffort);
        check(saved.profiles.size() == 4, "expected 4 profiles, got " + saved.profileNames());
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message + " (" + Path.of("config", "justbuild.json") + ")");
        }
    }
}
