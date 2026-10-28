package dev.justbuild.client;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class VersionHistoryScreen extends ToolScreen {
    private int page, rows;
    VersionHistoryScreen(Screen parent) { super("Visual version history", parent); }
    @Override protected void build() {
        var versions = ClientState.versions(); rows = Math.max(1, (height - 106) / 48);
        int pages = Math.max(1, (versions.size() + rows - 1) / rows); page = Math.min(page, pages - 1);
        for (int i = 0; i < rows && page * rows + i < versions.size(); i++) {
            var version = versions.get(page * rows + i);
            String date;
            try { date = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(version.createdAt())); }
            catch (RuntimeException e) { date = ""; }
            button("v" + version.number() + " · " + date + (version.current() ? " · Current" : " · Preview restore"),
                    left + 40, 60 + i * 48, panelWidth - 40, () -> { run("history preview " + version.id()); minecraft.gui.setScreen(null); })
                    .setTooltip(Tooltip.create(Component.literal(version.request() + "\nPreview changes against the current build. Instant Build confirms the restore; undo remains available.")));
        }
        int quarter = (panelWidth - 12) / 4;
        button("Back", left, height - 28, quarter, this::onClose);
        button("Compare", left + quarter + 4, height - 28, quarter, () -> minecraft.gui.setScreen(new FloorPreviewScreen(this)))
                .active = ClientState.preview() != null;
        button("Previous", left + 2 * (quarter + 4), height - 28, quarter, () -> { page--; rebuildWidgets(); }).active = page > 0;
        button("Next", left + 3 * (quarter + 4), height - 28, quarter, () -> { page++; rebuildWidgets(); }).active = page < pages - 1;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        text(g, "Recent saved versions. Preview a restore, compare, then Instant Build to apply.", 32, 55, Glass.MUTED);
        var versions = ClientState.versions();
        if (versions.isEmpty()) text(g, "Select a building to see its retained versions.", 70, height - 48, Glass.MUTED);
        for (int i = 0; i < rows && page * rows + i < versions.size(); i++) {
            var version = versions.get(page * rows + i); DesignIcon.draw(g, version.thumbnail(), left, 60 + i * 48, 34);
            g.text(font, font.plainSubstrByWidth(version.request(), panelWidth - 44), left + 42, 84 + i * 48, Glass.MUTED);
        }
    }
}
