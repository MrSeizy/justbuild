package dev.justbuild.client;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Select two saved buildings and describe the new work between them. */
public final class ConnectionsScreen extends ToolScreen {
    private String description = "a stone path between the entrances";
    private int page, rows, pages;
    private List<ClientState.Build> builds = List.of();
    ConnectionsScreen(Screen parent) { super("Connect buildings", parent); }
    @Override protected void build() {
        int quarter = (panelWidth - 12) / 4;
        EditBox request = new GlassEditBox(font, left, 60, panelWidth, 20, Component.literal("Connection request"));
        request.setMaxLength(200); request.setValue(description); request.setResponder(value -> description = value);
        addRenderableWidget(request);
        String[] labels = {"Path", "Bridge", "Fence", "Landscaping"};
        String[] prompts = {"a stone path between the entrances", "a walkable bridge connecting these buildings",
                "a fence with a gate joining the buildings", "coordinated gardens and a path between these buildings"};
        for (int i = 0; i < 4; i++) {
            int index = i;
            button(labels[i], left + i * (quarter + 4), 84, quarter, () -> { description = prompts[index]; request.setValue(description); });
        }
        builds = ClientState.builds();
        rows = Math.max(1, (height - 172) / 22); pages = Math.max(1, (builds.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < rows && page * rows + i < builds.size(); i++) {
            ClientState.Build build = builds.get(page * rows + i);
            int y = 124 + i * 22;
            button("A", left + panelWidth - 80, y, 38, () -> run("link a " + build.id()));
            button("B", left + panelWidth - 38, y, 38, () -> run("link b " + build.id()));
        }
        button("Back", left, height - 28, 80, this::onClose);
        if (pages > 1) {
            button("Previous", left + 84, height - 28, 66, () -> { page--; rebuildWidgets(); }).active = page > 0;
            button("Next", left + 154, height - 28, 66, () -> { page++; rebuildWidgets(); }).active = page < pages - 1;
        }
        button("Preview connection", left + 224, height - 28, panelWidth - 224,
                () -> { run("connect " + description.strip()); minecraft.setScreen(null); })
                .primary().active = ClientState.string("connectionA", null) != null && ClientState.string("connectionB", null) != null;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tick) {
        super.extractRenderState(graphics, mouseX, mouseY, tick);
        graphics.text(font, font.plainSubstrByWidth("A: " + ClientState.string("connectionA", "choose a building"), panelWidth), left, 30, 0xFFDDDDDD);
        graphics.text(font, font.plainSubstrByWidth("B: " + ClientState.string("connectionB", "choose a different building"), panelWidth), left, 43, 0xFFDDDDDD);
        graphics.text(font, "Both buildings are preserved. Choose A and B:", left, 110, 0xFFAAAAAA);
        for (int i = 0; i < rows && page * rows + i < builds.size(); i++) {
            graphics.text(font, font.plainSubstrByWidth(builds.get(page * rows + i).title(), panelWidth - 88),
                    left, 130 + i * 22, 0xFFEEEEEE);
        }
    }
}
