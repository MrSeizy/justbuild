package dev.justbuild.client;

import dev.justbuild.prompt.InteriorRequests;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class InteriorScreen extends ToolScreen {
    private int purpose, stage;
    private String detail = "";
    InteriorScreen(Screen parent) { super("Plan an interior", parent); }
    @Override protected void build() {
        int half = (panelWidth - 4) / 2;
        button("Purpose: " + InteriorRequests.PURPOSES.get(purpose), left, 40, half,
                () -> { purpose = (purpose + 1) % InteriorRequests.PURPOSES.size(); rebuildWidgets(); });
        button("Stage: " + InteriorRequests.STAGES.get(stage), left + half + 4, 40, half,
                () -> { stage = (stage + 1) % InteriorRequests.STAGES.size(); rebuildWidgets(); });
        var field = new GlassEditBox(font, left, 88, panelWidth, 20, Component.literal("Interior details"));
        field.setMaxLength(180); field.setHint(Component.literal("Optional: rooms, occupants, furniture…")); field.setValue(detail); field.setResponder(value -> detail = value); addRenderableWidget(field);
        button("Preview interior", left, 122, panelWidth, () -> { run("interior " + InteriorRequests.PURPOSES.get(purpose)
                + " " + InteriorRequests.STAGES.get(stage) + (detail.isBlank() ? "" : " " + detail.strip())); minecraft.setScreen(null); }).primary();
        button("Back", left, height - 28, panelWidth, this::onClose);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        text(g, "Complete: rooms + furniture. Or plan the layout first, then furnish.", 64, 86, 0xFFEEEEEE);
        text(g, "Uses your AI and keeps edits inside the building's outer shell and selected part. Includes lighting, clear doorways, and stairs. Each confirmed pass is undoable.", 154, height - 48, 0xFFAAAAAA);
    }
}
