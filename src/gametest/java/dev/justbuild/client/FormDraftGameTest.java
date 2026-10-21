package dev.justbuild.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.justbuild.prompt.BuildConstraints;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;

/** Overflow, independent form drafts, live updates and resizing through real Minecraft widgets. */
public final class FormDraftGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            int originalScale = context.computeOnClient(mc -> mc.options.guiScale().get());
            try {
                context.runOnClient(mc -> { mc.options.guiScale().set(3); mc.resizeGui(); });
                profiles(context);
                secretFields(context);
                requirements(context);
                otherForms(context);
                overflowLists(context);
            } finally {
                context.runOnClient(mc -> { mc.gui.setScreen(null); mc.options.guiScale().set(originalScale); mc.resizeGui(); });
            }
        }
    }

    private static void secretFields(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            var key = new GlassEditBox(mc.font, 10, 10, 200, 20, net.minecraft.network.chat.Component.literal("API key"));
            key.secret(); key.setValue("synthetic-secret-for-widget-test"); key.setFocused(true);
            check(!key.createNarrationMessage().getString().contains(key.getValue()), "narration included a credential");
            String clipboard = mc.keyboardHandler.getClipboard();
            try {
                mc.keyboardHandler.setClipboard("clipboard sentinel");
                int modifiers = InputConstants.MOD_CONTROL | InputConstants.MOD_SUPER;
                key.keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_A, InputConstants.KEYCODE_A, modifiers));
                key.keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_C, InputConstants.KEYCODE_C, modifiers));
                check(mc.keyboardHandler.getClipboard().equals("clipboard sentinel"), "copy exported a credential");
                key.keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_X, InputConstants.KEYCODE_X, modifiers));
                check(mc.keyboardHandler.getClipboard().equals("clipboard sentinel"), "cut exported a credential");
                check(key.getValue().equals("synthetic-secret-for-widget-test"), "blocked cut changed the field");
                key.keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_V, InputConstants.KEYCODE_V, modifiers));
                check(key.getValue().equals("clipboard sentinel"), "credential paste stopped working");
            } finally { ClientClipboard.set(clipboard); }
        });
    }

    private static void profiles(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            JsonObject state = new JsonObject(); JsonArray profiles = new JsonArray();
            for (int i = 1; i <= 20; i++) {
                JsonObject profile = new JsonObject(); profile.addProperty("name", "Profile " + i);
                profile.addProperty("provider", "claude"); profile.addProperty("model", ""); profiles.add(profile);
            }
            state.add("profiles", profiles); state.addProperty("profile", "Profile 20"); ClientState.update(state.toString());
            mc.gui.setScreen(new ProfilesScreen(new JustBuildScreen(), false));
            check(field(mc, "Profile name").getValue().equals("Profile 20"), "active profile was not selected");
            widget(mc, "★ Profile 20");
        });
        context.takeScreenshot("profiles-last-page");
        while (context.computeOnClient(mc -> widget(mc, "Prev").active)) context.clickScreenButton("Prev");
        Set<String> reached = new HashSet<>();
        while (true) {
            reached.addAll(context.computeOnClient(mc -> mc.gui.screen().children().stream()
                    .filter(w -> w instanceof GlassButton b && b.getMessage().getString().contains("Profile "))
                    .map(w -> ((GlassButton) w).getMessage().getString().replace("★ ", "")).toList()));
            if (!context.computeOnClient(mc -> widget(mc, "Next").active)) break;
            context.clickScreenButton("Next");
        }
        check(reached.size() == 20, "only " + reached.size() + " profiles were reachable");
        context.clickScreenButton("★ Profile 20");
        context.runOnClient(mc -> {
            var name = field(mc, "Profile name"); name.setValue("Renamed last"); mc.gui.screen().setFocused(name); name.moveCursorTo(4, false);
            widget(mc, "★ Renamed last");
            mc.gui.screen().resize(320, 240);
            check(mc.gui.screen().getFocused() == field(mc, "Profile name") && field(mc, "Profile name").getCursorPosition() == 4,
                    "resizing lost the profile field's focus or cursor");
        });
        context.clickScreenButton("Delete");
        context.clickScreenButton("Copy");
        context.runOnClient(mc -> {
            String name = field(mc, "Profile name").getValue(); widget(mc, name);
            check(name.contains("copy"), "copy was not visible and selected");
        });
        context.clickScreenButton("Delete"); context.clickScreenButton("New");
        context.runOnClient(mc -> { widget(mc, "New profile"); check(field(mc, "Profile name").getValue().equals("New profile"), "new profile not selected"); });
        context.takeScreenshot("profiles-new-last-page");
        context.runOnClient(mc -> field(mc, "Profile name").setValue(""));
        context.clickScreenButton("Prev"); context.clickScreenButton("Save");
        context.runOnClient(mc -> {
            check(mc.gui.screen() instanceof ProfilesScreen && field(mc, "Profile name").getValue().isEmpty(), "validation did not reveal the invalid profile");
            widget(mc, "(no name)");
        });
    }

    private static void requirements(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            constraints(null, 0, 0);
            mc.gui.setScreen(new ConstraintsScreen(new JustBuildScreen()));
            field(mc, "Maximum height").setValue("12"); field(mc, "Target floor").setValue("3");
            field(mc, "Allowed block").setValue("minecraft:oak_planks");
        });
        // The first Apply is height. The server response must leave the other two edits alone.
        context.clickScreenButton("Apply");
        context.waitFor(mc -> ClientState.constraints().maxHeight() == 12);
        context.runOnClient(mc -> {
            var screen = (ConstraintsScreen) mc.gui.screen();
            check(field(mc, "Target floor").getValue().equals("3"), "applying height erased the floor draft");
            var materials = field(mc, "Allowed block"); mc.gui.screen().setFocused(materials); materials.moveCursorTo(7, false);
            constraints(null, 14, 1); screen.stateChanged();
            check(field(mc, "Maximum height").getValue().equals("14"), "untouched height stopped following server changes");
            check(field(mc, "Target floor").getValue().equals("3"), "server update erased a draft");
            check(mc.gui.screen().getFocused() == materials && materials.getCursorPosition() == 7
                    && materials.getValue().equals("minecraft:oak_planks"), "server update replaced the focused field");
            screen.resize(320, 240);
            check(field(mc, "Allowed block").getValue().equals("minecraft:oak_planks")
                    && field(mc, "Allowed block").getCursorPosition() == 7
                    && screen.getFocused() == field(mc, "Allowed block"), "resize lost the draft, cursor or focus");
        });
        context.takeScreenshot("requirements-preserved-drafts");
        context.clickScreenButton("Clear requirements");
        context.waitFor(mc -> ClientState.constraints().equals(BuildConstraints.NONE));
        context.runOnClient(mc -> {
            check(field(mc, "Target floor").getValue().equals("0") && field(mc, "Allowed block").getValue().equals("any"), "explicit clear kept old drafts");
        });
        context.clickScreenButton("Clear requirements");
        context.runOnClient(mc -> {
            field(mc, "Target floor").setValue("4"); constraints(null, 0, 0);
            ((ConstraintsScreen) mc.gui.screen()).stateChanged();
            check(field(mc, "Target floor").getValue().equals("4"), "delayed clear acknowledgement erased newer typing");
            field(mc, "Target floor").setValue("9"); constraints("1234abcd", 24, 2);
            ((ConstraintsScreen) mc.gui.screen()).stateChanged();
            check(field(mc, "Target floor").getValue().equals("2"), "draft from another building leaked into the selection");
        });
    }

    private static void constraints(String id, int height, int floors) {
        JsonObject state = new JsonObject(), workshop = new JsonObject();
        if (id != null) state.addProperty("selectedBuild", id);
        workshop.add("constraints", new BuildConstraints(height, floors, List.of()).toJson());
        state.add("workshop", workshop); ClientState.update(state.toString());
    }

    private static void otherForms(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            var parent = new JustBuildScreen();
            List<ToolScreen> forms = List.of(new AddExistingScreen(parent), new InteriorScreen(parent), new TextureScreen(parent, null),
                    new NamedPartsScreen(parent), new ProjectsScreen(parent), new UsageScreen(parent), new ConnectionsScreen(parent));
            for (var screen : forms) {
                mc.gui.setScreen(screen);
                var field = (EditBox) screen.children().stream().filter(EditBox.class::isInstance).findFirst().orElseThrow();
                String label = field.getMessage().getString();
                field.setValue("12345"); screen.setFocused(field); field.moveCursorTo(2, false);
                screen.stateChanged();
                check(field(mc, label).getValue().equals("12345") && screen.getFocused() == field(mc, label)
                        && field(mc, label).getCursorPosition() == 2, screen.getTitle().getString() + " lost its live draft");
                screen.resize(320, 240);
                check(field(mc, label).getValue().equals("12345") && screen.getFocused() == field(mc, label)
                        && field(mc, label).getCursorPosition() == 2, screen.getTitle().getString() + " lost its resized draft");
            }
            mc.gui.setScreen(new WorkshopScreen(parent));
        });
        context.takeScreenshot("workshop-grouped-tools");
        context.getInput().pressKey(InputConstants.KEY_C);
        context.waitForScreen(ConstraintsScreen.class);
    }

    private static void overflowLists(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            JsonObject state = new JsonObject(), workshop = new JsonObject(), project = new JsonObject();
            JsonArray team = new JsonArray(), builds = new JsonArray();
            for (int i = 0; i < 16; i++) {
                JsonObject member = new JsonObject(); member.addProperty("id", new java.util.UUID(0, i).toString());
                member.addProperty("name", "Member " + i); team.add(member);
                JsonObject build = new JsonObject(); build.addProperty("id", String.format("%08x", i));
                build.addProperty("title", "Building " + i); build.addProperty("versions", 1); build.addProperty("request", "fixture"); builds.add(build);
            }
            project.addProperty("id", "0123abcd"); project.addProperty("name", "Village"); project.addProperty("mine", true);
            project.add("theme", dev.justbuild.prompt.ProjectTheme.EMPTY.toJson()); project.add("builds", new JsonArray());
            project.add("collaborators", team); JsonArray projects = new JsonArray(); projects.add(project);
            workshop.add("projects", projects); state.add("workshop", workshop); state.add("builds", builds);
            state.addProperty("provider", "claude"); state.addProperty("profile", "Claude"); state.addProperty("model", "old");
            ClientState.update(state.toString()); mc.gui.setScreen(new ProjectTeamScreen(new JustBuildScreen(), "0123abcd"));
        });
        Set<String> members = new HashSet<>();
        do {
            members.addAll(context.computeOnClient(mc -> mc.gui.screen().children().stream().filter(c -> c instanceof AbstractWidget w
                    && w.getMessage().getString().startsWith("Member ")).map(c -> ((AbstractWidget)c).getMessage().getString()).toList()));
            if (!context.computeOnClient(mc -> widget(mc, "Next").active)) break;
            context.clickScreenButton("Next");
        } while (true);
        check(members.size() == 16, "some project collaborators were unreachable");
        context.runOnClient(mc -> mc.gui.setScreen(new JustBuildScreen())); context.clickScreenButton("History");
        Set<String> buildings = new HashSet<>();
        do {
            buildings.addAll(context.computeOnClient(mc -> mc.gui.screen().children().stream().filter(c -> c instanceof AbstractWidget w
                    && w.getMessage().getString().startsWith("“Building ")).map(c -> ((AbstractWidget)c).getMessage().getString()).toList()));
            if (!context.computeOnClient(mc -> widget(mc, "Next").active)) break;
            context.clickScreenButton("Next");
        } while (true);
        check(buildings.size() == 16, "some historical buildings were unreachable");
        context.clickScreenButton("Settings");
        context.runOnClient(mc -> {
            var model = field(mc, "Model"); model.setValue("unsaved-model"); mc.gui.screen().setFocused(model); model.moveCursorTo(3, false);
            ((JustBuildScreen)mc.gui.screen()).stateChanged(); mc.gui.screen().resize(320, 240);
            check(field(mc, "Model").getValue().equals("unsaved-model") && field(mc, "Model").getCursorPosition() == 3
                    && mc.gui.screen().getFocused() == field(mc, "Model"), "settings refresh lost the unsaved model");
        });
    }
    private static EditBox field(Minecraft mc, String prefix) {
        return (EditBox) mc.gui.screen().children().stream().filter(w -> w instanceof EditBox e && e.getMessage().getString().startsWith(prefix)).findFirst().orElseThrow();
    }
    private static AbstractWidget widget(Minecraft mc, String label) {
        return (AbstractWidget) mc.gui.screen().children().stream().filter(w -> w instanceof AbstractWidget a && a.getMessage().getString().equals(label)).findFirst().orElseThrow();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
