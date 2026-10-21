package dev.justbuild.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps JustBuild status readable inside the viewport, using vanilla's position and fade. */
@Mixin(Hud.class)
abstract class StatusOverlayMixin {
    @WrapOperation(method = "extractOverlayMessage", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V"))
    private void justbuild$wrapStatus(GuiGraphicsExtractor graphics, Font font, Component message,
            int x, int y, int textWidth, int color, Operation<Void> original) {
        if (!message.getString().startsWith("JustBuild · ")) {
            original.call(graphics, font, message, x, y, textWidth, color);
            return;
        }

        int width = Math.max(1, graphics.guiWidth() - 24);
        List<FormattedText> lines = font.splitIgnoringLanguage(message, width);
        int count = Math.min(3, lines.size());
        int top = y - (count - 1) * (font.lineHeight + 1);
        List<Component> rows = new ArrayList<>(count);
        int widest = 0;
        for (int i = 0; i < count; i++) {
            FormattedText line = lines.get(i);
            if (i == count - 1 && lines.size() > count) {
                line = FormattedText.composite(font.substrByWidth(line, Math.max(0, width - font.width("…"))),
                        FormattedText.of("…", message.getStyle()));
            }
            MutableComponent row = Component.empty();
            line.visit((style, text) -> {
                row.append(Component.literal(text).setStyle(style));
                return Optional.empty();
            }, message.getStyle());
            rows.add(row);
            widest = Math.max(widest, font.width(row));
        }
        // One close-fitting, 40%-opaque sheet; don't stack vanilla's configurable text backdrop on top.
        int alpha = (color >>> 24) * 102 / 255;
        graphics.fill(-widest / 2 - 6, top - 3, (widest + 1) / 2 + 6, y + font.lineHeight + 3, alpha << 24);
        for (int i = 0; i < rows.size(); i++) {
            Component row = rows.get(i);
            graphics.text(font, row, -font.width(row) / 2, top + i * (font.lineHeight + 1), color, true);
        }
    }
}
