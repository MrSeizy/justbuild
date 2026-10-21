package dev.justbuild.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

final class MaterialPickerScreen extends ToolScreen {
    private record Choice(String id, ItemStack icon, String label) { }
    private final boolean source;
    private final Consumer<String> choose;
    private String search = "";
    private int page, rows, pages, half;
    private EditBox searchBox;
    private List<Choice> visible = List.of();
    MaterialPickerScreen(Screen parent, boolean source, Consumer<String> choose) {
        this(parent, source ? "Building palette" : "Choose a replacement block", source, choose);
    }
    /** {@code choose} may open the next screen itself; otherwise picking returns to {@code parent}. */
    MaterialPickerScreen(Screen parent, String title, boolean source, Consumer<String> choose) {
        super(title, parent); this.source = source; this.choose = choose;
    }
    @Override protected void build() {
        searchBox = new GlassEditBox(font, left, 34, panelWidth, 20, Component.literal("Search blocks")); searchBox.setMaxLength(80);
        searchBox.setValue(search); searchBox.setHint(Component.literal("Search block names or ids")); addRenderableWidget(searchBox);
        searchBox.setResponder(value -> { search = value; page = 0; rebuildWidgets(); setInitialFocus(searchBox); });
        List<Choice> choices = new ArrayList<>();
        if (source) for (var material : ClientState.materials()) {
            Identifier key = Identifier.tryParse(material.id());
            if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) continue;
            Block block = BuiltInRegistries.BLOCK.getValue(key);
            choices.add(new Choice(material.id(), block.asItem().getDefaultInstance(), block.getName().getString() + " × " + material.count()));
        } else BuiltInRegistries.BLOCK.stream().filter(b -> !b.asItem().getDefaultInstance().isEmpty()).forEach(b ->
                choices.add(new Choice(BuiltInRegistries.BLOCK.getKey(b).toString(), b.asItem().getDefaultInstance(), b.getName().getString())));
        String needle = search.toLowerCase(Locale.ROOT);
        var filtered = choices.stream().filter(c -> c.id().contains(needle) || c.label().toLowerCase(Locale.ROOT).contains(needle)).toList();
        rows = Math.max(1, (height - 118) / 24); half = (panelWidth - 4) / 2; int count = rows * 2;
        pages = Math.max(1, (filtered.size() + count - 1) / count); page = Math.min(page, pages - 1);
        visible = filtered.subList(Math.min(page * count, filtered.size()), Math.min((page + 1) * count, filtered.size()));
        for (int i = 0; i < visible.size(); i++) {
            var choice = visible.get(i); int col = i % 2, row = i / 2;
            button("   " + font.plainSubstrByWidth(choice.label(), half - 40), left + col * (half + 4), 66 + row * 24, half, () -> { choose.accept(choice.id()); if (minecraft.gui.screen() == this) onClose(); })
                    .setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(choice.id())));
        }
        int third = (panelWidth - 8) / 3;
        button("Back", left, height - 28, third, this::onClose);
        button("Previous", left + third + 4, height - 28, third, () -> { page--; rebuildWidgets(); }).active = page > 0;
        button("Next", left + 2 * (third + 4), height - 28, third, () -> { page++; rebuildWidgets(); }).active = page < pages - 1;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        for (int i = 0; i < visible.size(); i++) {
            var choice = visible.get(i); int bx = left + i % 2 * (half + 4), by = 66 + i / 2 * 24;
            g.item(choice.icon(), bx + 3, by + 2);
        }
        if (visible.isEmpty()) g.text(font, source ? "No palette entries. Refresh the building palette." : "No matching blocks.", left, 72, 0xFFAAAAAA);
        else if (pages > 1) {
            String text = "Page " + (page + 1) + " / " + pages;
            g.text(font, text, left + panelWidth - font.width(text), 12, 0xFFAAAAAA);
        }
    }
}
