package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ProjectsScreen extends ToolScreen {
    private String name = "";
    private int page;
    ProjectsScreen(Screen parent) { super("Village projects", parent); }
    @Override protected void build() {
        var field = new GlassEditBox(font, left, 36, panelWidth - 94, 20, Component.literal("Project name"));
        field.setMaxLength(60); field.setHint(Component.literal("New project name")); field.setValue(name); addRenderableWidget(field);
        var create = button("Create", left + panelWidth - 90, 36, 90, () -> run("project create " + name.strip())); create.active = !name.isBlank();
        field.setResponder(value -> { name = value; create.active = !name.isBlank(); });
        var projects = ClientState.projects(); int rows = Math.max(1, (height - 122) / 24), pages = Math.max(1, (projects.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < rows && page * rows + i < projects.size(); i++) {
            var p = projects.get(page * rows + i);
            button((p.id().equals(ClientState.activeProject()) ? "✓ " : "") + p.name() + " · " + p.count() + " builds"
                    + (p.mine() ? "" : " · shared by " + p.ownerName()), left, 76 + i * 24, panelWidth,
                    () -> { run("project select " + p.id()); minecraft.gui.setScreen(new ProjectDetailsScreen(this, p.id())); });
        }
        int quarter = (panelWidth - 12) / 4;
        button("Back", left, height - 28, quarter, this::onClose);
        button("Clear active", left + quarter + 4, height - 28, quarter, () -> run("project clear"));
        button("Previous", left + 2 * (quarter + 4), height - 28, quarter, () -> { page--; rebuildWidgets(); }).active = page > 0;
        button("Next", left + 3 * (quarter + 4), height - 28, quarter, () -> { page++; rebuildWidgets(); }).active = page < pages - 1;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        g.text(font, "Select a project to edit its shared style and buildings.", left, 62, 0xFFAAAAAA);
        if (ClientState.projects().isEmpty()) text(g, "Create a project for your village. Its new buildings, paths, and landscaping will share a style and palette.", 84, height - 48, 0xFFEEEEEE);
    }
}
