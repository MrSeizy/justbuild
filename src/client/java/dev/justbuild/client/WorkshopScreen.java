package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class WorkshopScreen extends ToolScreen {
    private final java.util.Map<Integer, Button> shortcuts = new java.util.HashMap<>();
    WorkshopScreen(Screen parent) { super("Building workshop", parent); }
    @Override protected void build() {
        shortcuts.clear();
        boolean selected = ClientState.string("selectedBuild", null) != null; int half = (panelWidth - 4) / 2;
        boolean newPreview = ClientState.preview() != null && ClientState.preview().movable();
        tool("Add existing build", left, 64, half,
                "Make an existing structure editable with JustBuild. With the wand in Box mode, left-click to start, then right-click to include the footprint and roof. Check the purple box and name it. Saves its original state without changing blocks.",
                () -> minecraft.gui.setScreen(new AddExistingScreen(this)));
        tool("Materials", left, 88, half,
                "Swap saved building materials or reroll procedural mixes. A new-build preview opens texture controls directly. Changes preview before placement and respect your zone, selected part, and locks.",
                () -> {
                    if (newPreview) minecraft.gui.setScreen(new TextureScreen(this, null));
                    else { run("materials"); minecraft.gui.setScreen(new MaterialsScreen(this)); }
                }).active = selected || newPreview;
        tool("Repair", left, 112, half,
                "Restore blocks from a saved version or checkpoint. Missing blocks only fills gaps; All differences also replaces changed materials. Select a build, choose a mode, then preview and confirm.",
                () -> minecraft.gui.setScreen(new RepairScreen(this))).active = selected;
        tool("Plan interiors", left, 136, half,
                "Ask your AI to plan rooms, furniture, lighting, and routes inside the selected build. Choose a purpose and plan everything, just the layout, or just furnishing. Preview before applying.",
                () -> minecraft.gui.setScreen(new InteriorScreen(this))).active = selected;
        tool("Village projects", left, 160, half,
                "Group builds that belong together and give them a shared style and block palette. Create or select a project, then add builds. Each build keeps its own edit history and undo.",
                () -> minecraft.gui.setScreen(new ProjectsScreen(this)));
        tool("Walkability", left + half + 4, 112, half,
                "Check whether you can walk through the selected build or preview. Finds unreachable areas and checks doors, stairs, and headroom. Red markers show possible trouble spots; no blocks are changed.",
                () -> { run("walkability"); minecraft.gui.setScreen(new WalkabilityScreen(this)); })
                .active = selected || ClientState.preview() != null;
        tool("Requirements", left + half + 4, 64, half, "Set height, target floors, allowed materials, and preserved parts.",
                () -> minecraft.gui.setScreen(new ConstraintsScreen(this)));
        tool("Named parts", left + half + 4, 88, half, "Save and reuse named selections within this building.",
                () -> minecraft.gui.setScreen(new NamedPartsScreen(this))).active = selected;
        tool("Version history", left + half + 4, 136, half, "See saved versions, preview their differences, and restore with undo.",
                () -> minecraft.gui.setScreen(new VersionHistoryScreen(this))).active = selected;
        tool("AI usage", left + half + 4, 160, half, "Calls, reported tokens, AI time, review rounds, and optional session limits.",
                () -> minecraft.gui.setScreen(new UsageScreen(this)));
        tool("Back", left, height - 28, panelWidth, "Return to the previous screen.", this::onClose);
    }
    private Button tool(String label, int x, int y, int width, String help, Runnable action) {
        Button button = button(label, x, y, width, action);
        int shortcut = switch (label) {
            case "Add existing build" -> 'A'; case "Materials" -> 'M'; case "Repair" -> 'R'; case "Plan interiors" -> 'I';
            case "Village projects" -> 'P'; case "Walkability" -> 'W'; case "Requirements" -> 'C';
            case "Named parts" -> 'N'; case "Version history" -> 'V'; case "AI usage" -> 'U'; default -> 0;
        };
        if (shortcut != 0) { shortcuts.put(shortcut, button); help += " Shortcut: " + (char) shortcut + "."; }
        button.setTooltip(Tooltip.create(Component.literal(help)));
        return button;
    }
    @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        Button button = shortcuts.get(Character.toUpperCase(event.shortcutKey()));
        if (event.modifiers() == 0 && button != null && button.active) { button.onPress(event); return true; }
        return super.keyPressed(event);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        g.text(font, font.plainSubstrByWidth(ClientState.string("selectedTitle", "Select a building, or add one you built by hand."), panelWidth), left, 34, 0xFFEEEEEE);
        g.text(font, "Build & edit", left, 50, Glass.MUTED);
        g.text(font, "Inspect & protect", left + (panelWidth - 4) / 2 + 4, 50, Glass.MUTED);
        text(g, "Repairs and restores open a preview before placing.", 186, height - 43, Glass.MUTED);
    }
}
