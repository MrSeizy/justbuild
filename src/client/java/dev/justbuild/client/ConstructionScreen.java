package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Project controls and the whole build's remaining materials, visible as soon as the panel opens. */
final class ConstructionScreen extends ConstructionMenuScreen {
    private static final int MATERIAL_TOP = 141, ROW_HEIGHT = 12, CONFIRM_TICKS = 80;
    private boolean closing;
    private int closingTicks;
    private String controls;
    private int page;
    ConstructionScreen() { super("Construction project", null); }
    private int rows() { return Math.max(1, (height - 66 - MATERIAL_TOP - font.lineHeight) / ROW_HEIGHT + 1); }
    private int pages(ConstructionClient.State s) { return Math.max(1, (s.materials().size() + rows() - 1) / rows()); }
    @Override protected void build() {
        var s = ConstructionClient.state();
        if (s == null) { buildProblem(); return; }
        page = Math.min(page, pages(s) - 1);
        controls = controlKey(s);
        int third = (panelWidth - 8) / 3, quarter = (panelWidth - 12) / 4;
        String[] modes = {"Guide", "Assist", "Cobble"};
        String[] hints = {"Build by hand. Matching blocks automatically count toward progress.",
                "Aim at a ghost and use to place from inventory. Sneak for normal use.",
                "Cobble builds one course at a time using the linked chest, and steps past blocks that need your hands."};
        for (int i = 0; i < modes.length; i++) {
            String mode = modes[i];
            var b = button(mode, left + i * (third + 4), 47, third, () -> run("construction " + mode.toLowerCase(java.util.Locale.ROOT))).choice();
            b.setTooltip(Tooltip.create(Component.literal(hints[i])));
            if (s.mode().equalsIgnoreCase(mode)) b.current();
        }
        var pause = button(s.paused() ? "Resume" : "Pause", left, 71, quarter, () -> run("construction " + (s.paused() ? "resume" : "pause")));
        if (s.paused() && !s.mode().equals("GUIDE")) pause.primary();
        if (s.mode().equals("GUIDE")) pause.setTooltip(Tooltip.create(Component.literal(
                "Guide never places blocks itself. Pause holds Assist and Cobble when you switch to them.")));
        button(s.currentLayer() ? "Ghosts: layer" : "Ghosts: all", left + quarter + 4, 71, quarter, () -> run("construction layer"))
                .setTooltip(Tooltip.create(Component.literal("Show ghosts for the current layer or the entire blueprint. Material totals below always cover the whole build.")));
        var supply = button(s.chest() == null ? "Link chest" : "Relink chest", left + 2 * (quarter + 4), 71, quarter, this::linkChest);
        if (s.mode().equals("COBBLE") && s.chest() == null) supply.primary();
        supply.setTooltip(Tooltip.create(Component.literal("Links the chest or barrel you are looking at. Otherwise the panel closes "
                + "and the next chest or barrel you open becomes the supply." + (s.chest() == null ? "" : " Current supply: " + s.chest().toShortString()))));
        button("Unlink", left + 3 * (quarter + 4), 71, quarter, () -> run("construction unlink")).active = s.chest() != null;
        button("Materials", left + panelWidth - 76, 104, 76, () -> minecraft.gui.setScreen(new ConstructionMaterialsScreen(this)))
                .setTooltip(Tooltip.create(Component.literal("Search all materials or show only missing supplies.")));
        button("Previous", left, height - 62, third, () -> { page--; rebuildWidgets(); }).active = page > 0;
        button("Next", left + 2 * (third + 4), height - 62, third, () -> { page++; rebuildWidgets(); }).active = page + 1 < pages(s);
        button("JustBuild", left, height - 24, third, () -> minecraft.gui.setScreen(new JustBuildScreen()));
        endButton(left + third + 4, third, "Click twice to end the project and remove its blueprint. Placed blocks and supplies stay.");
        button("Keep building", left + 2 * (third + 4), height - 24, third, this::onClose).primary();
    }
    /** A saved project that could not be read still holds new plans back, so it gets a way out here. */
    private void buildProblem() {
        int third = (panelWidth - 8) / 3;
        if (ConstructionClient.problem() == null) { button("Back", left, height - 24, panelWidth, this::onClose); return; }
        button("JustBuild", left, height - 24, third, () -> minecraft.gui.setScreen(new JustBuildScreen()));
        endButton(left + third + 4, third, "Click twice to set the unreadable file aside. It is renamed, not deleted.");
        button("Back", left + 2 * (third + 4), height - 24, third, this::onClose);
    }
    private void endButton(int x, int width, String tooltip) {
        button(closing ? "End project?" : "End project", x, height - 24, width, () -> {
            if (closing) run("construction finish"); else { closing = true; closingTicks = 0; rebuildWidgets(); }
        }).danger().setTooltip(Tooltip.create(Component.literal(tooltip)));
    }
    private void linkChest() {
        boolean aiming = minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && minecraft.level != null
                && (minecraft.level.getBlockEntity(hit.getBlockPos()) instanceof ChestBlockEntity
                || minecraft.level.getBlockEntity(hit.getBlockPos()) instanceof BarrelBlockEntity);
        run("construction chest");
        // The server waits for the next container the player opens; the panel would hold the mouse.
        if (!aiming) onClose();
    }
    @Override public void tick() {
        super.tick();
        if (closing && ++closingTicks > CONFIRM_TICKS) { closing = false; rebuildWidgets(); }
    }
    private String controlKey(ConstructionClient.State s) { return s.mode() + ":" + s.paused() + ":" + s.currentLayer() + ":" + s.chest() + ":" + pages(s); }
    @Override void stateChanged() {
        var s = ConstructionClient.state();
        if (s == null || !controlKey(s).equals(controls)) rebuildWidgets();
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        var s = ConstructionClient.state();
        if (s != null && x >= left && x < left + panelWidth && y >= MATERIAL_TOP && y < height - 62 && vertical != 0) {
            int next = Math.clamp(page + (vertical > 0 ? -1 : 1), 0, pages(s) - 1);
            if (next != page) { page = next; rebuildWidgets(); }
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        super.extractRenderState(g, mx, my, dt); var s = ConstructionClient.state();
        if (s == null) {
            String problem = ConstructionClient.problem();
            if (problem == null) return;
            int y = text(g, "Your saved construction project could not be read.", 34, 60, Glass.TITLE);
            y = text(g, "Reason: " + problem, y + 4, y + 40, Glass.TEXT);
            text(g, closing ? "End project again to set the file aside. Nothing in the world changes."
                    : "Until it is set aside, JustBuild holds new plans back. End project renames the file so a newer "
                    + "JustBuild, or you, can still recover it. You can also use /justbuild construction finish.", y + 8, height - 52, Glass.MUTED);
            return;
        }
        g.text(font, font.plainSubstrByWidth(s.title() + " · " + s.done() + " / " + s.total(), panelWidth), left, 30, Glass.TITLE);
        g.fill(left, 42, left + panelWidth, 44, 0x554C5261);
        g.fill(left, 42, left + (int) ((long) panelWidth * s.done() / Math.max(1, s.total())), 44, Glass.TITLE);
        String status = closing ? "End project again to remove only the blueprint." : ConstructionClient.statusText(s, true);
        boolean cobble = s.cobble();
        String source = cobble ? s.chest() == null ? "Link a chest" : "Chest linked" : "Your inventory";
        int sourceWidth = font.width(source) + 8;
        g.text(font, font.plainSubstrByWidth(status, panelWidth - sourceWidth - 8), left, 95, Glass.TEXT);
        g.text(font, source, left + panelWidth - sourceWidth + 4, 95, cobble && s.chest() == null ? ConstructionClient.SHORT : ConstructionClient.READY);
        if (mx >= left && mx < left + panelWidth - sourceWidth && my >= 95 && my < 104)
            g.setTooltipForNextFrame(font, font.split(Component.literal(status), panelWidth), mx, my);
        String heading = "Whole build · materials left" + (s.manual() > 0 ? " · " + s.manual() + " manual" : "");
        g.text(font, font.plainSubstrByWidth(heading, panelWidth - 82), left, 111, Glass.TITLE);
        if (s.manual() > 0 && mx >= left && mx < left + panelWidth - 82 && my >= 109 && my < 124)
            g.setTooltipForNextFrame(font, font.split(Component.literal(s.manual() + " remaining tasks need manual clearing, water, or special preparation."), panelWidth), mx, my);
        ConstructionMaterialList.draw(g, font, s, s.materials(), page * rows(), rows(), left, 127, MATERIAL_TOP, ROW_HEIGHT, panelWidth, mx, my);
        g.centeredText(font, (page + 1) + " / " + pages(s), width / 2, height - 56, Glass.MUTED);
    }
}
