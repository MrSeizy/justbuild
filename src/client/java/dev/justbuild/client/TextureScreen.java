package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class TextureScreen extends ToolScreen {
    private String seed = "", weight = "1", material;
    TextureScreen(Screen parent, String material) { super("Procedural material texture", parent); this.material = material; }

    @Override protected void build() {
        var seedField = new GlassEditBox(font, left, 38, panelWidth - 130, 20, Component.literal("Texture seed"));
        seedField.setMaxLength(11); seedField.setValue(seed); seedField.setHint(Component.literal("Seed (blank: next)"));
        addRenderableWidget(seedField);
        var reroll = button("Preview reroll", left + panelWidth - 124, 38, 124,
                () -> { run("materials reroll" + (seed.isBlank() ? "" : " " + seed)); minecraft.gui.setScreen(null); });
        seedField.setResponder(value -> { seed = value; reroll.active = value.isBlank() || integer(value, Integer.MIN_VALUE, Integer.MAX_VALUE); });
        reroll.active = seed.isBlank() || integer(seed, Integer.MIN_VALUE, Integer.MAX_VALUE);
        button("Choose mix material", left, 76, panelWidth, () -> minecraft.gui.setScreen(new MaterialPickerScreen(this, false, id -> material = id)));
        var weightField = new GlassEditBox(font, left, 126, panelWidth - 130, 20, Component.literal("Relative weight, 0 to 100"));
        weightField.setMaxLength(3); weightField.setValue(weight); addRenderableWidget(weightField);
        var apply = button("Preview weight", left + panelWidth - 124, 126, 124,
                () -> { run("materials weight " + material + " " + weight); minecraft.gui.setScreen(null); });
        weightField.setResponder(value -> { weight = value; apply.active = material != null && integer(value, 0, 100); });
        apply.active = material != null && integer(weight, 0, 100);
        button("Back", left, height - 28, panelWidth, this::onClose);
    }

    private static boolean integer(String value, int min, int max) {
        try { int n = Integer.parseInt(value); return n >= min && n <= max; }
        catch (NumberFormatException e) { return false; }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        g.text(font, font.plainSubstrByWidth(material == null ? "Choose a material from an existing mix." : "Weight (0–100): " + material, panelWidth), left, 106, 0xFFEEEEEE);
        text(g, "No AI call. Repeats existing mixes; keeps manual changes. Default weights: main 2, others 1. Zero excludes.", 166, height - 48, 0xFFAAAAAA);
    }
}
