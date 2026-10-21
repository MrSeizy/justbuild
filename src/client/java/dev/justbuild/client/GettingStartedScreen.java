package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** A repeatable walkthrough driven by current world state; opening it never starts a request. */
final class GettingStartedScreen extends ToolScreen {
    private final JustBuildScreen panel;
    private int step;

    GettingStartedScreen(JustBuildScreen parent) {
        super("Your first build", parent);
        panel = parent;
        step = ClientState.preview() != null || ClientState.placing() != null ? 3
                : ClientState.zone() != null || ClientState.planning() != null || ClientState.question() != null ? 2 : 0;
    }

    @Override protected void build() {
        String[] labels = {"1 · AI", "2 · Area", "3 · Request", "4 · Preview"};
        int w = (panelWidth - 12) / 4;
        for (int i = 0; i < labels.length; i++) {
            int page = i;
            var b = button(labels[i], left + i * (w + 4), 34, w, () -> { step = page; rebuildWidgets(); });
            if (step == i) b.current();
        }
        String action = switch (step) {
            case 0 -> "Open AI settings";
            case 1 -> "Equip wand: Zone";
            case 2 -> "Write a request";
            default -> ClientState.preview() != null ? "Inspect preview" : "Return to Create";
        };
        button(action, left, height - 100, panelWidth, () -> {
            if (step == 0) panel.showSettings();
            else if (step == 1) { run("zone wand"); minecraft.gui.setScreen(null); }
            else panel.showCreate();
        });
        int third = (panelWidth - 8) / 3;
        button("Previous", left, height - 28, third, () -> { step--; rebuildWidgets(); }).active = step > 0;
        button("Back to panel", left + third + 4, height - 28, third, this::onClose);
        button("Next", left + 2 * (third + 4), height - 28, third, () -> { step++; rebuildWidgets(); }).active = step < 3;
    }

    String guidance() {
        return switch (step) {
            case 0 -> "Choose the AI that will plan your build in Settings. Open its profile and use Test connection, then save. "
                    + "On a multiplayer server, Settings explains whether builds use your AI or the server's.";
            case 1 -> "Hold the Zone wand. Use your attack control on one corner and your use control on the opposite corner. "
                    + "The white outline is the work area. A zone is optional: without one, new builds go in front of you or where you look.";
            case 2 -> "Describe the size, materials and entrance you want. Use ↗ for a longer request, or start from Templates. "
                    + "Build sends it to the AI; Cobble, your robot helper, works nearby. "
                    + "Apply edits the selected build; choose New build first for a separate building.";
            default -> ClientState.flag("autoBuild")
                    ? "Auto-build is ON: ordinary results place immediately. Turn it off in Settings to inspect previews first. "
                        + "Undo can reverse your latest change. Alternatives still wait for confirmation."
                    : "Inspect the ghost preview, change counts and Checks. Use Before/After to compare. "
                        + "Build it places the blocks; Discard keeps the world as it is. Undo can reverse your latest change.";
        };
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float tick) {
        super.extractRenderState(g, mouseX, mouseY, tick);
        text(g, guidance(), 64, height - 108, Glass.TEXT);
        String status = switch (step) {
            case 0 -> "Current AI: " + ClientState.string("aiLabel", ClientState.string("profile", "waiting for server"));
            case 1 -> ClientState.zone() != null ? "Zone selected. Hold the wand to see it." : "No zone selected yet.";
            case 2 -> ClientState.planning() != null ? "Your request is being planned."
                    : ClientState.question() != null ? "The AI is waiting for your answer." : "You decide when to send the request.";
            default -> ClientState.preview() != null ? "A preview is ready to inspect."
                    : ClientState.placing() != null ? "Blocks are being placed." : "No preview is waiting.";
        };
        text(g, status, height - 72, height - 48, Glass.TITLE);
    }
}
