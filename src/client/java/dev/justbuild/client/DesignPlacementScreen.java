package dev.justbuild.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Copies a saved design; preview and placement are performed by the server without an AI request. */
public final class DesignPlacementScreen extends FormScreen {
    private static final int CHIPS_TOP = 58;
    private final Screen parent;
    private final long opened = ClientState.lineCount();
    private ClientState.Design design;
    private int rotation;
    /** Block id to block id, in the order picked; sent as the command's from=to,… substitutions. */
    private final Map<String, String> swaps = new LinkedHashMap<>();
    private String folder;
    private int left;
    private int panelWidth;
    private int chipRows;
    private int chipPage;

    DesignPlacementScreen(Screen parent, ClientState.Design design) {
        super(Component.literal("Place design"));
        this.parent = parent;
        this.design = design;
        this.folder = design.folder();
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        panelWidth = Math.min(440, width - 20);
        left = (width - panelWidth) / 2;
        int half = (panelWidth - 4) / 2, third = (panelWidth - 8) / 3;
        chipRows = Math.max(1, Math.min(4, (height - 189) / 22));
        addRenderableWidget(GlassButton.builder(Component.literal("Rotation: " + rotation + "°"), b -> {
            rotation = (rotation + 90) % 360;
            b.setMessage(Component.literal("Rotation: " + rotation + "°"));
        }).bounds(left, 34, half, 20).build());
        Button swap = GlassButton.builder(Component.literal("Swap materials…"), b -> minecraft.gui.setScreen(
                new MaterialPickerScreen(this, "Block to swap out", false, from -> minecraft.gui.setScreen(
                        new MaterialPickerScreen(this, "Replace " + name(from) + " with…", false, to -> {
                            if (!from.equals(to)) swaps.put(from, to);
                        })))))
                .bounds(left + half + 4, 34, half, 20).build();
        swap.active = swaps.size() < 16;
        swap.setTooltip(Tooltip.create(Component.literal(swap.active
                ? "Pick a block in the design, then the block to use instead." : "Remove a swap to add another.")));
        addRenderableWidget(swap);
        int perPage = chipRows * 2, pages = Math.max(1, (swaps.size() + perPage - 1) / perPage);
        chipPage = Math.min(chipPage, pages - 1);
        var entries = new ArrayList<>(swaps.entrySet());
        for (int i = 0; i < perPage && chipPage * perPage + i < entries.size(); i++) {
            var entry = entries.get(chipPage * perPage + i);
            String text = name(entry.getKey()) + " → " + name(entry.getValue()) + " ✕";
            Button chip = GlassButton.builder(Component.literal(fit(text, half - 8)), b -> { swaps.remove(entry.getKey()); rebuildWidgets(); })
                    .bounds(left + i % 2 * (half + 4), CHIPS_TOP + i / 2 * 22, half, 20).build();
            chip.setTooltip(Tooltip.create(Component.literal("Remove this swap: " + entry.getKey() + " → " + entry.getValue())));
            addRenderableWidget(chip);
        }
        int fieldY = CHIPS_TOP + chipRows * 22 + 14;
        EditBox folderField = new GlassEditBox(font, left, fieldY, panelWidth - 100, 20, Component.literal("Design folder"));
        folderField.setMaxLength(60); folderField.setValue(folder); folderField.setHint(Component.literal("Unfiled"));
        folderField.setResponder(value -> folder = value); addRenderableWidget(folderField);
        addRenderableWidget(GlassButton.builder(Component.literal("Save folder"), b -> run("design folder " + design.id()
                + (folder.isBlank() ? "" : " " + folder.strip()))).bounds(left + panelWidth - 96, fieldY, 96, 20).build());
        addRenderableWidget(GlassButton.builder(Component.literal("Export design"), b -> minecraft.gui.setScreen(new DesignExportScreen(this, design)))
                .bounds(left, fieldY + 24, half, 20).build());
        addRenderableWidget(GlassButton.builder(Component.literal("Shared library"), b -> {
            run("design refresh"); minecraft.gui.setScreen(new LibraryImportScreen(this));
        }).bounds(left + half + 4, fieldY + 24, half, 20).build());
        addRenderableWidget(GlassButton.builder(Component.literal("Back"), b -> onClose())
                .bounds(left, height - 28, third, 20).build());
        if (pages > 1) {
            addRenderableWidget(GlassButton.builder(Component.literal("‹"), b -> { chipPage--; rebuildWidgets(); })
                    .bounds(left + third + 4, height - 28, (third - 4) / 2, 20)
                    .tooltip(Tooltip.create(Component.literal("Previous material swaps"))).build()).active = chipPage > 0;
            addRenderableWidget(GlassButton.builder(Component.literal("›"), b -> { chipPage++; rebuildWidgets(); })
                    .bounds(left + third + 8 + (third - 4) / 2, height - 28, (third - 4) / 2, 20)
                    .tooltip(Tooltip.create(Component.literal("Next material swaps"))).build()).active = chipPage < pages - 1;
        }
        addRenderableWidget(GlassButton.builder(Component.literal("Preview copy"), b -> {
            run("design place " + design.id() + " " + rotation + (swaps.isEmpty() ? "" : " " + substitutions()));
            minecraft.gui.setScreen(null);
        }).bounds(left + panelWidth - third, height - 28, third, 20).primary().build());
    }

    /** The command's format: oak_planks=spruce_planks,oak_stairs=spruce_stairs (vanilla ids without the namespace). */
    private String substitutions() {
        return String.join(",", swaps.entrySet().stream().map(e -> shortId(e.getKey()) + "=" + shortId(e.getValue())).toList());
    }

    private static String shortId(String id) { return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id; }

    private static String name(String id) {
        Identifier key = Identifier.tryParse(id);
        return key != null && BuiltInRegistries.BLOCK.containsKey(key) ? BuiltInRegistries.BLOCK.getValue(key).getName().getString() : id;
    }

    private String fit(String text, int width) {
        return font.width(text) <= width ? text : font.plainSubstrByWidth(text, width - font.width("…")) + "…";
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }

    private void run(String text) { if (minecraft.player != null) minecraft.player.connection.sendCommand("justbuild " + text); }
    void stateChanged() {
        ClientState.designs().stream().filter(d -> d.id().equals(design.id())).findFirst().ifPresent(d -> design = d);
        rebuildWidgets();
    }

    /** The glass sheet instead of the vanilla blur: the world stays visible around the panel. */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Glass.sheet(graphics, left, panelWidth, height);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, font.plainSubstrByWidth("Copy: " + design.name(), panelWidth), width / 2, 18, Glass.TITLE);
        if (swaps.isEmpty()) graphics.text(font, "No material swaps: the copy uses the design's own blocks.", left, CHIPS_TOP + 6, 0xFFAAAAAA);
        int fieldY = CHIPS_TOP + chipRows * 22 + 14;
        graphics.text(font, "Folder (blank means Unfiled):", left, fieldY - 11, 0xFFAAAAAA);
        String text = "No AI call: copies use your zone or crosshair. Rotate clockwise from your facing, then adjust the preview.";
        int y = fieldY + 50;
        for (var line : font.split(Component.literal(text), panelWidth)) {
            if (y + font.lineHeight > height - 48) break;
            graphics.text(font, line, left, y, 0xFFAAAAAA);
            y += font.lineHeight + 1;
        }
        StatusLine.draw(graphics, font, opened, left, height - 38, panelWidth, mouseX, mouseY);
    }
}
