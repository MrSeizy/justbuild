package dev.justbuild.client;

import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ConstraintsScreen extends ToolScreen {
    private final Draft heightDraft = new Draft(), floorsDraft = new Draft(), materialsDraft = new Draft();
    private String target;
    private boolean initialized;
    private GlassButton preserve;

    ConstraintsScreen(Screen parent) { super("Build requirements", parent); }
    @Override protected void build() {
        refreshDrafts();
        row("Maximum height (0 = unset)", "height", heightDraft, 58, 3);
        row("Target floor count (0 = unset)", "floors", floorsDraft, 92, 2);
        row("Allowed block IDs, comma-separated; any = unrestricted", "materials", materialsDraft, 126, 4096);
        int half = (panelWidth - 4) / 2;
        preserve = button("Preserve selected part", left, 152, half, () -> minecraft.gui.setScreen(new EditingToolsScreen(this, true)));
        preserve.active = ClientState.part() != null;
        button("Clear requirements", left + half + 4, 152, half, () -> {
            // Clear this draft now. Text entered after this click must survive the eventual server reply.
            heightDraft.receive("0", true); floorsDraft.receive("0", true); materialsDraft.receive("any", true);
            run("constraints clear");
        });
        button("Back", left, height - 28, panelWidth, this::onClose);
    }

    private void refreshDrafts() {
        var value = ClientState.constraints();
        String nextTarget = ClientState.string("selectedBuild", null);
        boolean changedTarget = !initialized || !Objects.equals(target, nextTarget);
        heightDraft.receive(Integer.toString(value.maxHeight()), changedTarget);
        floorsDraft.receive(Integer.toString(value.targetFloors()), changedTarget);
        materialsDraft.receive(value.materials().isEmpty() ? "any" : String.join(",", value.materials()), changedTarget);
        target = nextTarget;
        initialized = true;
    }

    /** Unchanged fields follow the server. Other fields retain their drafts, focus, and cursor. */
    @Override void stateChanged() {
        refreshDrafts();
        if (preserve != null) preserve.active = ClientState.part() != null;
    }

    private void row(String label, String key, Draft draft, int y, int limit) {
        var field = new GlassEditBox(font, left, y, panelWidth - 76, 20, Component.literal(label));
        field.setMaxLength(limit); field.setValue(draft.value); field.setResponder(v -> draft.value = v);
        draft.field = field; addRenderableWidget(field);
        button("Apply", left + panelWidth - 72, y, 72, () -> run("constraints " + key + " " + field.getValue().strip()));
    }

    private static final class Draft {
        private String value = "", saved = "";
        private EditBox field;

        void receive(String next, boolean reset) {
            if (reset || value.equals(saved)) value = next;
            saved = next;
            if (field != null && !field.getValue().equals(value)) field.setValue(value);
        }
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        g.text(font, font.plainSubstrByWidth(ClientState.string("selectedTitle", "Defaults for your next builds"), panelWidth), left, 33, Glass.MUTED);
        g.text(font, "Maximum height · 0 = unset", left, 46, Glass.TEXT);
        g.text(font, "Target floor count · design guidance", left, 80, Glass.TEXT);
        g.text(font, "Allowed materials · comma-separated block IDs or any", left, 114, Glass.TEXT);
        text(g, "Height starts at the build origin. New materials are checked; locked parts stay unchanged.", 178, height - 52, Glass.MUTED);
    }
}
