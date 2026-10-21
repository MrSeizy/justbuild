package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class AddExistingScreen extends ToolScreen {
    private String name = "";
    AddExistingScreen(Screen parent) { super("Add existing build", parent); }
    @Override protected void build() {
        button("Equip wand: Box", left, 36, panelWidth, () -> { run("box wand"); minecraft.gui.setScreen(null); })
                .setTooltip(Tooltip.create(Component.literal("Equips the Builder Wand in purple Box mode. Sneak-right-click in the air to switch modes. Left-click to start a new selection; each right-click grows its box to include that block. Select the footprint, then the roof. Blocks stay intact.")));
        var field = new GlassEditBox(font, left, 94, panelWidth, 20, Component.literal("Building name"));
        field.setMaxLength(60); field.setHint(Component.literal("Name this building")); field.setValue(name); addRenderableWidget(field);
        var add = button("Add build and save original", left, 122, panelWidth, () -> run("add " + name.strip())).primary();
        add.active = ClientState.part() != null && !name.isBlank(); field.setResponder(value -> { name = value; add.active = ClientState.part() != null && !name.isBlank(); });
        button("Back", left, height - 28, panelWidth, this::onClose);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        text(g, ClientState.flag("selectingPart") ? "Start point selected. Right-click to include the footprint and roof."
                : ClientState.string("partLabel", "Select the whole building with the wand in Box mode, including its floor and roof."), 64, 90, 0xFFD88BFF);
        text(g, "Left-click starts again; each right-click grows the purple box. Check it includes the whole build before adding it. "
                + "Saves an original checkpoint; blocks stay in place. Containers, signs, and locked areas are excluded.", 152, height - 48, 0xFFAAAAAA);
    }
}
