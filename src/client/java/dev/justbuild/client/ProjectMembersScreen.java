package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

final class ProjectMembersScreen extends ToolScreen {
    private final String projectId;
    private int page;
    ProjectMembersScreen(Screen parent, String id) { super("Project buildings", parent); projectId = id; }
    @Override protected void build() {
        var project = ClientState.projects().stream().filter(p -> p.id().equals(projectId)).findFirst().orElse(null);
        var members = project == null ? java.util.List.<ClientState.Build>of() : project.members();
        int rows = Math.max(1, (height - 116) / 26), pages = Math.max(1, (members.size() + rows - 1) / rows); page = Math.min(page, pages - 1);
        for (int i = 0; i < rows && page * rows + i < members.size(); i++) {
            var build = members.get(page * rows + i);
            button(build.title() + " · v" + build.versions(), left, 66 + i * 26, panelWidth,
                    () -> { run("select " + build.id()); minecraft.gui.setScreen(new BuildDetailsScreen(this, build.id())); });
        }
        int third = (panelWidth - 8) / 3;
        button("Back", left, height - 28, third, this::onClose);
        button("Previous", left + third + 4, height - 28, third, () -> { page--; rebuildWidgets(); }).active = page > 0;
        button("Next", left + 2 * (third + 4), height - 28, third, () -> { page++; rebuildWidgets(); }).active = page < pages - 1;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        text(g, "Each building keeps its own versions, checkpoints, and undo. Select an existing build, then use Add selected build in project settings.", 32, 62, 0xFFAAAAAA);
    }
}
