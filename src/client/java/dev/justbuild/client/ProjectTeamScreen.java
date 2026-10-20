package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Co-building: the project's owner invites players, who may then change every building in the project. */
final class ProjectTeamScreen extends ToolScreen {
    private final String id;
    private String name = "";
    private int page;

    ProjectTeamScreen(Screen parent, String id) { super("Project team", parent); this.id = id; }

    @Override protected void build() {
        var project = ClientState.projects().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
        int half = (panelWidth - 4) / 2, third = (panelWidth - 8) / 3;
        if (project != null && project.mine()) {
            var field = new GlassEditBox(font, left, 52, panelWidth - 94, 20, Component.literal("Player name"));
            field.setMaxLength(36); field.setHint(Component.literal("Player name or UUID")); field.setValue(name);
            addRenderableWidget(field);
            var invite = button("Invite", left + panelWidth - 90, 52, 90, () -> { run("project invite " + name.strip()); name = ""; });
            invite.active = !name.isBlank() && project.team().size() < 16;
            field.setResponder(value -> { name = value; invite.active = !name.isBlank() && project.team().size() < 16; });
            int rows = Math.max(1, (height - 140) / 24);
            int pages = Math.max(1, (project.team().size() + rows - 1) / rows);
            page = Math.min(page, pages - 1);
            for (int i = 0; page * rows + i < project.team().size() && i < rows; i++) {
                var member = project.team().get(page * rows + i);
                int y = 92 + i * 24;
                addRenderableWidget(new net.minecraft.client.gui.components.StringWidget(left, y + 6, panelWidth - 94, 9,
                        Component.literal(member.name()), font));
                button("Remove", left + panelWidth - 90, y, 90, () -> run("project uninvite " + member.id())).danger();
            }
            if (pages > 1) {
                button("Previous", left + third + 4, height - 28, third, () -> { page--; rebuildWidgets(); }).active = page > 0;
                button("Next", left + 2 * (third + 4), height - 28, third, () -> { page++; rebuildWidgets(); }).active = page < pages - 1;
            }
        } else if (project != null) {
            button("Leave project", left, 52, half, () -> { run("project leave " + id); onClose(); }).danger();
        }
        button("Back", left, height - 28, third, this::onClose);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        var project = ClientState.projects().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
        if (project == null) { g.text(font, "Loading project…", left, 28, 0xFFEEEEEE); return; }
        g.text(font, font.plainSubstrByWidth(project.name(), panelWidth), left, 28, 0xFFEEEEEE);
        if (project.mine()) {
            g.text(font, "Players on the team can change every building in this project.", left, 40, 0xFFAAAAAA);
            if (project.team().isEmpty()) g.text(font, "No one else yet.", left, 98, 0xFFAAAAAA);
        } else {
            text(g, "Shared with you by " + project.ownerName() + ". You can change its buildings; your own undo steps stay yours.",
                    40, 50, 0xFFAAAAAA);
        }
    }
}
