package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Names, checkpoints, and reusable designs for the explicitly selected build. */
public final class BuildDetailsScreen extends FormScreen {
    private final Screen parent;
    private final String buildId;
    private final long opened = ClientState.lineCount();
    private String name = "";
    private String checkpointName = "";
    private String designName = "";
    private boolean nameLoaded;
    private int page;
    private int left;
    private int panelWidth;

    BuildDetailsScreen(Screen parent, String buildId) {
        super(Component.literal("Manage build"));
        this.parent = parent;
        this.buildId = buildId;
    }

    @Override public boolean isPauseScreen() { return false; }

    private boolean ready() { return buildId != null && buildId.equals(ClientState.string("selectedBuild", null)); }

    @Override protected void init() {
        panelWidth = Math.min(440, width - 20);
        left = (width - panelWidth) / 2;
        if (ready() && !nameLoaded) { name = ClientState.string("selectedTitle", ""); nameLoaded = true; }
        addNameRow("Build name", name, 36, "Rename", value -> name = value, "rename");
        addNameRow("Checkpoint name", checkpointName, 62, "Save checkpoint", value -> checkpointName = value, "checkpoint save");
        addNameRow("Design name", designName, 88, "Save design", value -> designName = value, "design save");
        var checkpoints = ready() ? ClientState.list("checkpoints") : java.util.List.<String>of();
        int rows = Math.max(1, (height - 160) / 22);
        int pages = Math.max(1, (checkpoints.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < rows && page * rows + i < checkpoints.size(); i++) {
            String checkpoint = checkpoints.get(page * rows + i);
            addRenderableWidget(GlassButton.builder(Component.literal("Restore: " + checkpoint), b -> {
                run("checkpoint restore " + checkpoint);
                minecraft.setScreen(null);
            }).bounds(left, 130 + i * 22, panelWidth, 20).build());
        }
        int bottom = height - 26;
        addRenderableWidget(GlassButton.builder(Component.literal("Back"), b -> onClose()).bounds(left, bottom, 80, 20).build());
        addRenderableWidget(GlassButton.builder(Component.literal("Workshop"), b -> minecraft.setScreen(new WorkshopScreen(this)))
                .bounds(left + 84, bottom, Math.min(120, panelWidth - 254), 20).build());
        if (pages > 1) {
            Button previous = GlassButton.builder(Component.literal("Previous"), b -> { page--; rebuildWidgets(); })
                    .bounds(left + panelWidth - 166, bottom, 80, 20).build();
            previous.active = page > 0;
            addRenderableWidget(previous);
            Button next = GlassButton.builder(Component.literal("Next"), b -> { page++; rebuildWidgets(); })
                    .bounds(left + panelWidth - 80, bottom, 80, 20).build();
            next.active = page < pages - 1;
            addRenderableWidget(next);
        }
    }

    private void addNameRow(String label, String value, int y, String action,
            java.util.function.Consumer<String> responder, String command) {
        EditBox field = new GlassEditBox(font, left, y, panelWidth - 124, 20, Component.literal(label));
        field.setMaxLength(60);
        field.setHint(Component.literal(label));
        field.setValue(value);
        field.setResponder(responder);
        field.active = ready();
        addRenderableWidget(field);
        Button button = GlassButton.builder(Component.literal(action), b -> run(command + " " + field.getValue().strip()))
                .bounds(left + panelWidth - 120, y, 120, 20).build();
        button.active = ready();
        addRenderableWidget(button);
    }

    private void run(String command) {
        if (ready() && minecraft.player != null) minecraft.player.connection.sendCommand("justbuild " + command);
    }

    void stateChanged() { rebuildWidgets(); }

    @Override public void onClose() { minecraft.setScreen(parent); }

    /** The glass sheet instead of the vanilla blur: the world stays visible around the panel. */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Glass.sheet(graphics, left, panelWidth, height);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, font.plainSubstrByWidth(ready() ? "Editing: " + ClientState.string("selectedTitle", "")
                : "Waiting for the build selection…", panelWidth), width / 2, 14, Glass.TITLE);
        graphics.text(font, "Restore a checkpoint: preview first, then confirm. Undo stays available.", left, 116, 0xFFAAAAAA);
        if (ready() && ClientState.list("checkpoints").isEmpty()) graphics.text(font,
                "No checkpoints yet. Save a name above before changing this build.", left, 136, 0xFFAAAAAA);
        StatusLine.draw(graphics, font, opened, left, height - 36, panelWidth, mouseX, mouseY);
    }
}
