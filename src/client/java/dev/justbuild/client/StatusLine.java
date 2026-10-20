package dev.justbuild.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.util.FormattedCharSequence;

/** The newest JustBuild line since a screen opened, in its own colours, on at most two lines above the buttons. */
final class StatusLine {
    private StatusLine() { }

    /** {@code lastY} is where the bottom line goes; a second line sits above it. */
    static void draw(GuiGraphicsExtractor g, Font font, long since, int x, int lastY, int width, int mouseX, int mouseY) {
        Component line = ClientState.lastLineSince(since);
        if (line == null) return;
        Component text = withoutPrefix(line);
        List<FormattedText> parts = font.splitIgnoringLanguage(text, width);
        if (parts.isEmpty()) return;
        boolean cut = parts.size() > 2;
        List<FormattedCharSequence> shown = new ArrayList<>();
        shown.add(Language.getInstance().getVisualOrder(parts.getFirst()));
        if (parts.size() > 1) {
            FormattedText second = cut ? FormattedText.composite(font.substrByWidth(parts.get(1), width - font.width("…")),
                    FormattedText.of("…")) : parts.get(1);
            shown.add(Language.getInstance().getVisualOrder(second));
        }
        int top = lastY - (shown.size() - 1) * (font.lineHeight + 1), y = top;
        for (FormattedCharSequence row : shown) {
            g.text(font, row, x, y, 0xFFAAAAAA);
            y += font.lineHeight + 1;
        }
        if (cut && mouseX >= x && mouseX < x + width && mouseY >= top && mouseY < y) {
            g.setTooltipForNextFrame(font, font.split(text, Math.min(width, 300)), mouseX, mouseY);
        }
    }

    /** Every line starts with the gold "[JustBuild] " tag; inside the builder's own screens it only takes room. */
    private static Component withoutPrefix(Component line) {
        if (!(line.getContents() instanceof PlainTextContents.LiteralContents(String tag)) || !tag.equals("[JustBuild] ")
                || line.getSiblings().isEmpty()) return line;
        MutableComponent rest = Component.empty();
        line.getSiblings().forEach(rest::append);
        return rest;
    }
}
