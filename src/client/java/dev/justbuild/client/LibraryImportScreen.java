package dev.justbuild.client;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared portable files can be imported into any world run by this game or server. */
public final class LibraryImportScreen extends ToolScreen {
    private int page;
    private boolean includeAir;
    private List<String> files = List.of();
    LibraryImportScreen(Screen parent) { super("Shared design library", parent); }
    @Override protected void build() {
        files = ClientState.list("libraryFiles").stream()
                .filter(file -> file.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}\\.(justbuild\\.json|litematic|schem)")).toList();
        button("Include schematic air: " + (includeAir ? "On" : "Off"), left, 76, panelWidth, () -> { includeAir = !includeAir; rebuildWidgets(); })
                .setTooltip(Tooltip.create(Component.literal("On: air in the schematic can clear blocks when placed. Off: skip air. Both modes preview before placement. Entity and container data are omitted.")));
        int rows = Math.max(1, (height - 154) / 22), pages = Math.max(1, (files.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < rows && page * rows + i < files.size(); i++) {
            String file = files.get(page * rows + i);
            button("Import " + font.plainSubstrByWidth(file, panelWidth - 48), left, 104 + i * 22, panelWidth,
                    () -> run("design import " + file + (includeAir && !file.endsWith(".justbuild.json") ? " air" : ""))).setTooltip(Tooltip.create(Component.literal(file)));
        }
        int quarter = (panelWidth - 12) / 4;
        button("Back", left, height - 28, quarter, this::onClose);
        button("Refresh", left + quarter + 4, height - 28, quarter, () -> run("design refresh"));
        button("Previous", left + 2 * (quarter + 4), height - 28, quarter, () -> { page--; rebuildWidgets(); }).active = page > 0;
        button("Next", left + 3 * (quarter + 4), height - 28, quarter, () -> { page++; rebuildWidgets(); }).active = page < pages - 1;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tick) {
        super.extractRenderState(graphics, mouseX, mouseY, tick);
        text(graphics, "Copy .litematic, .schem, or .justbuild.json files into justbuild/library in this game's folder "
                + "(the server's folder in multiplayer), then Refresh.", 32, 72, 0xFFAAAAAA);
        if (files.isEmpty()) graphics.text(font, "No portable design files found.", left, 108, 0xFFEEEEEE);
    }
}
