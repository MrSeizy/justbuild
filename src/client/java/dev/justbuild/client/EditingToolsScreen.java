package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The wand box, durable protection, and entry to the two-building connection workflow. */
public final class EditingToolsScreen extends ToolScreen {
    private boolean locks;
    private String lockName = "";
    private int page;
    EditingToolsScreen(Screen parent) { super("Editing tools", parent); }
    EditingToolsScreen(Screen parent, boolean locks) { this(parent); this.locks = locks; }
    @Override protected void build() {
        int third = (panelWidth - 8) / 3;
        var parts = button("Parts", left, 34, third, () -> { locks = false; rebuildWidgets(); });
        var lockedAreas = button("Locked areas", left + third + 4, 34, third, () -> { locks = true; rebuildWidgets(); });
        (locks ? lockedAreas : parts).current();
        button("Connections", left + 2 * (third + 4), 34, third, () -> minecraft.gui.setScreen(new ConnectionsScreen(this)));
        if (!locks) {
            button("Wand: Box", left, 66, third, () -> { run("box wand"); minecraft.gui.setScreen(null); })
                    .setTooltip(Tooltip.create(Component.literal("Equips the Builder Wand in purple Box mode. Sneak-left-click clears the box. Sneak-right-click in the air switches modes. Left-click starts again; each right-click grows the box to include that block. Select the footprint, then the roof.")));
            button("Start box", left + third + 4, 66, third, () -> { run("box 1"); minecraft.gui.setScreen(null); })
                    .setTooltip(Tooltip.create(Component.literal("Start a new selection at the block under your crosshair, or underfoot.")));
            button("Include point", left + 2 * (third + 4), 66, third, () -> { run("box 2"); minecraft.gui.setScreen(null); })
                    .setTooltip(Tooltip.create(Component.literal("Grow the box to include the block under your crosshair, or underfoot. Repeat to include more points.")));
            String[] labels = {"Select roof", "Select wall", "Select room"};
            String[] kinds = {"roof", "wall", "room"};
            String[] help = {"Select the upper roof band of the saved build. Check the purple box before editing.",
                    "Point at a wall of the selected build to select its vertical plane. Check the purple box before editing.",
                    "Stand inside a room and point at its surface. Selects the enclosed space and a one-block shell. For open or large rooms, use the wand in Part mode."};
            for (int i = 0; i < 3; i++) {
                int index = i;
                var select = button(labels[i], left + i * (third + 4), 90, third, () -> { run("box " + kinds[index]); minecraft.gui.setScreen(null); });
                select.active = ClientState.string("selectedBuild", null) != null;
                select.setTooltip(Tooltip.create(Component.literal(help[i])));
            }
            int half = (panelWidth - 4) / 2;
            button("Named parts", left, 114, half, () -> minecraft.gui.setScreen(new NamedPartsScreen(this)));
            button("Clear box", left + half + 4, 114, half, () -> run("box clear"));
        } else {
            EditBox name = new GlassEditBox(font, left, 66, panelWidth - 94, 20, Component.literal("Locked area name"));
            name.setMaxLength(60); name.setValue(lockName);
            name.setHint(Component.literal("Name the selected area")); addRenderableWidget(name);
            var lockButton = button("Lock area", left + panelWidth - 90, 66, 90, () -> run("lock " + lockName.strip()));
            lockButton.active = ClientState.part() != null && !lockName.isBlank();
            name.setResponder(value -> {
                lockName = value;
                lockButton.active = ClientState.part() != null && !value.isBlank();
            });
            var mine = ClientState.locks().stream().filter(ClientState.Locked::mine).toList();
            int rows = Math.max(1, (height - 166) / 22), pages = Math.max(1, (mine.size() + rows - 1) / rows);
            page = Math.min(page, pages - 1);
            for (int i = 0; i < rows && page * rows + i < mine.size(); i++) {
                var lock = mine.get(page * rows + i);
                button("Unlock: " + lock.name(), left, 116 + i * 22, panelWidth, () -> run("unlock " + lock.id())).danger();
            }
            if (pages > 1) {
                button("Previous", left + third + 4, height - 28, third, () -> { page--; rebuildWidgets(); }).active = page > 0;
                button("Next", left + 2 * (third + 4), height - 28, third, () -> { page++; rebuildWidgets(); }).active = page < pages - 1;
            }
        }
        button("Back", left, height - 28, third, this::onClose);
        if (!locks) button("Workshop", left + third + 4, height - 28, panelWidth - third - 4, () -> minecraft.gui.setScreen(new WorkshopScreen(this)));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tick) {
        super.extractRenderState(graphics, mouseX, mouseY, tick);
        if (locks) {
            graphics.text(font, "Select part corners first. Locks persist with this world.", left, 96, 0xFFAAAAAA);
            if (ClientState.locks().stream().noneMatch(ClientState.Locked::mine)) graphics.text(font, "You have no locked areas.", left, 120, 0xFFAAAAAA);
        } else {
            String selection = ClientState.flag("selectingPart") ? "Start point selected. Right-click another block to include it."
                    : ClientState.string("partLabel", "No box selected; edits can use the whole build area.");
            int y = text(graphics, selection, 146, height - 48, 0xFFD88BFF);
            text(graphics, "Box mode: left-click starts again; right-click grows the box. Select the footprint, then the roof. "
                    + "Sneak-left-click clears the selection. Check the purple box before editing.", y + 5, height - 48, 0xFFAAAAAA);
        }
    }
}
