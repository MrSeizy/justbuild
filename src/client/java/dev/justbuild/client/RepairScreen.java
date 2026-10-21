package dev.justbuild.client;

import java.util.ArrayList;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

final class RepairScreen extends ToolScreen {
    private int checkpoint;
    private boolean missing = true;
    RepairScreen(Screen parent) { super("Repair building", parent); }
    @Override protected void build() {
        var snapshots = new ArrayList<String>(); snapshots.add("Current version"); snapshots.addAll(ClientState.list("checkpoints"));
        checkpoint = Math.min(checkpoint, snapshots.size() - 1);
        button("Compare: " + snapshots.get(checkpoint), left, 44, panelWidth, () -> { checkpoint = (checkpoint + 1) % snapshots.size(); rebuildWidgets(); });
        int half = (panelWidth - 4) / 2;
        var missingOnly = button("Missing blocks only", left, 74, half, () -> { missing = true; rebuildWidgets(); });
        var all = button("All differences", left + half + 4, 74, half, () -> { missing = false; rebuildWidgets(); });
        (missing ? missingOnly : all).current();
        button("Preview repair", left, 130, panelWidth, () -> { run("repair " + (missing ? "missing" : "all")
                + (checkpoint == 0 ? "" : " " + snapshots.get(checkpoint))); minecraft.gui.setScreen(null); }).primary();
        button("Back", left, height - 28, panelWidth, this::onClose);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        text(g, missing ? "Fill missing blocks and preserve manual replacements." : "Also replace changed materials and clear differences recorded in the snapshot.", 102, 126, 0xFFEEEEEE);
        text(g, "The selected part and locked areas apply. Preview before confirming; Undo restores the actual previous blocks. No AI call.", 164, height - 48, 0xFFAAAAAA);
    }
}
