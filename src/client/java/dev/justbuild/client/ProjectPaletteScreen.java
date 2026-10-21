package dev.justbuild.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** A project's shared palette, chosen with the block picker: up to 12 blocks its new buildings lean on. */
final class ProjectPaletteScreen extends ToolScreen {
    private static final int MAX = 12;
    private final String id;

    ProjectPaletteScreen(Screen parent, String id) { super("Shared palette", parent); this.id = id; }

    private List<String> palette() {
        return ClientState.projects().stream().filter(p -> p.id().equals(id)).findFirst()
                .map(p -> p.theme().palette()).orElse(List.of());
    }

    private void save(List<String> blocks) {
        run("project palette " + (blocks.isEmpty() ? "clear" : String.join(",", blocks)));
    }

    @Override protected void build() {
        List<String> blocks = palette();
        int third = (panelWidth - 8) / 3;
        int rows = Math.max(1, Math.min(4, (height - 150) / 22));
        for (int i = 0; i < blocks.size() && i < rows * 3; i++) {
            String block = blocks.get(i);
            Button chip = button(fit(name(block) + " ✕", third - 8), left + (i % 3) * (third + 4), 40 + (i / 3) * 22, third, () -> {
                List<String> rest = new ArrayList<>(blocks); rest.remove(block); save(rest);
            });
            chip.setTooltip(Tooltip.create(Component.literal("Remove " + name(block) + " (" + block + ")")));
        }
        int below = 40 + rows * 22 + 6;
        Button add = button("Add block…", left, below, third, () -> minecraft.gui.setScreen(new MaterialPickerScreen(this,
                "Add to the shared palette", false, block -> {
                    List<String> next = new ArrayList<>(palette());
                    if (!next.contains(block) && next.size() < MAX) next.add(block);
                    save(next);
                })));
        add.active = blocks.size() < MAX;
        add.setTooltip(Tooltip.create(Component.literal(blocks.size() < MAX ? "Pick a block for this project's buildings"
                : "The palette holds up to " + MAX + " blocks")));
        Button building = button("From selected build", left + third + 4, below, third, () -> run("project palette building"));
        building.active = ClientState.string("selectedBuild", null) != null;
        building.setTooltip(Tooltip.create(Component.literal(building.active ? "Use the selected building's most common blocks"
                : "Select a build first")));
        button("Clear", left + 2 * (third + 4), below, third, () -> save(List.of())).danger().active = !blocks.isEmpty();
        button("Back", left, height - 28, third, this::onClose);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        List<String> blocks = palette();
        g.text(font, blocks.isEmpty() ? "No shared palette yet: new buildings choose their own blocks."
                : blocks.size() + " of " + MAX + " blocks. New buildings in this project lean on these.", left, 28, 0xFFAAAAAA);
        int rows = Math.max(1, Math.min(4, (height - 150) / 22));
        if (blocks.size() > rows * 3) g.text(font, "+" + (blocks.size() - rows * 3) + " more", left, 40 + rows * 22 - 4, 0xFFAAAAAA);
    }

    private String fit(String text, int width) {
        return font.width(text) <= width ? text : font.plainSubstrByWidth(text, width - font.width("…")) + "…";
    }

    static String name(String id) {
        Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        return key != null && BuiltInRegistries.BLOCK.containsKey(key) ? BuiltInRegistries.BLOCK.getValue(key).getName().getString() : id;
    }
}
