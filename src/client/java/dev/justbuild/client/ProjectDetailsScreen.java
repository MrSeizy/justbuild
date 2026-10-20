package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ProjectDetailsScreen extends ToolScreen {
    private final String id;
    ProjectDetailsScreen(Screen parent, String id) { super("Project settings", parent); this.id = id; }
    @Override protected void build() {
        var project = ClientState.projects().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
        boolean ready = project != null && id.equals(ClientState.activeProject()); int half = (panelWidth - 4) / 2, third = (panelWidth - 8) / 3;
        button("Style: " + (project == null ? "auto" : project.theme().style()), left, 42, third, () -> {
            var styles = ClientState.list("styles"); if (!styles.isEmpty()) run("project style " + styles.get((styles.indexOf(project.theme().style()) + 1) % styles.size()));
        }).active = ready;
        button("Buildings", left + third + 4, 42, third, () -> minecraft.setScreen(new ProjectMembersScreen(this, id))).active = ready;
        button("Team", left + 2 * (third + 4), 42, third, () -> minecraft.setScreen(new ProjectTeamScreen(this, id))).active = ready;
        button("Shared palette…", left, 82, panelWidth, () -> minecraft.setScreen(new ProjectPaletteScreen(this, id))).active = ready;
        button("Add selected build", left, 108, half, () -> run("project add")).active = ready && ClientState.string("selectedBuild", null) != null;
        button("Remove selected", left + half + 4, 108, half, () -> run("project remove")).danger().active = ready && ClientState.string("selectedBuild", null) != null;
        button("Back", left, height - 28, third, this::onClose);
        button("New building", left + third + 4, height - 28, third, () -> { run("select clear"); run("zone clear"); minecraft.setScreen(new JustBuildScreen()); }).active = ready;
        button("Connections", left + 2 * (third + 4), height - 28, third, () -> minecraft.setScreen(new ConnectionsScreen(this))).active = ready;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        var project = ClientState.projects().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
        g.text(font, font.plainSubstrByWidth(project == null ? "Loading project…" : project.name()
                + (project.mine() ? "" : " · shared by " + project.ownerName()), panelWidth), left, 28, 0xFFEEEEEE);
        var palette = project == null ? java.util.List.<String>of() : project.theme().palette();
        g.text(font, font.plainSubstrByWidth(palette.isEmpty() ? "Shared palette: none yet" : "Shared palette: "
                + String.join(", ", palette.stream().map(ProjectPaletteScreen::name).toList()), panelWidth), left, 68, 0xFFAAAAAA);
        text(g, "New builds and connections join the active project. For landscaping, choose New building and describe the shared outdoor area.", 140, height - 48, 0xFFAAAAAA);
    }
}
