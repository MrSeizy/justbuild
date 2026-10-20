package dev.justbuild.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/**
 * A text box drawn as a rounded inset field. It is built with the field's bounds, like a vanilla box. An unbordered
 * EditBox draws its text at its own top-left, so the editable part sits inside with padding (and after the icon, if
 * any), and a click anywhere on the field still focuses it. The field follows the widget's live position, so
 * {@code setX}/{@code setY} move both. {@code setWidth} takes the inner text width, and hover (cursor and tooltip)
 * covers only the text strip.
 */
final class GlassEditBox extends EditBox {
    private static final int PAD = 6;
    private final int insetLeft;
    private final int insetTop;
    private final int fieldHeight;
    private final Identifier icon;
    private boolean secret;

    /** Visual reveal does not opt a credential into narration or clipboard export. Paste still works. */
    void secret() { secret = true; }

    @Override protected MutableComponent createNarrationMessage() {
        return secret ? Component.literal("API key field. " + (getValue().isEmpty() ? "Empty." : "Value entered."))
                : super.createNarrationMessage();
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (secret && (event.isCopy() || event.isCut())) return true;
        return super.keyPressed(event);
    }

    GlassEditBox(Font font, int x, int y, int width, int height, Component message) {
        this(font, x, y, width, height, message, null);
    }

    GlassEditBox(Font font, int x, int y, int width, int height, Component message, Identifier icon) {
        super(font, x + PAD + (icon == null ? 0 : 12), y + (height - 8) / 2,
                width - 2 * PAD - (icon == null ? 0 : 12), 9, message);
        insetLeft = PAD + (icon == null ? 0 : 12);
        insetTop = (height - 8) / 2;
        fieldHeight = height;
        this.icon = icon;
        setBordered(false);
    }

    /** The whole rounded field; the widget's own bounds are the text strip inside it. */
    ScreenRectangle field() {
        return new ScreenRectangle(getX() - insetLeft, getY() - insetTop, getWidth() + insetLeft + PAD, fieldHeight);
    }

    /** Hints use the glass hint colour; vanilla's default dark grey is nearly invisible on the field. */
    @Override
    public void setHint(Component hint) {
        super.setHint(hint.copy().withColor(Glass.HINT_RGB));
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        ScreenRectangle field = field();
        return isActive() && mouseX >= field.left() && mouseX < field.right() && mouseY >= field.top() && mouseY < field.bottom();
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ScreenRectangle field = field();
        Glass.field(g, field.left(), field.top(), field.width(), field.height(), isFocused());
        if (icon != null) Glass.icon(g, icon, field.left() + PAD, field.top() + (field.height() - 9) / 2, 0xFFFFFFFF);
        // Vanilla clips entered text, but its empty-field hint can extend across neighbouring controls.
        g.enableScissor(getX(), field.top(), getX() + getWidth() + 1, field.bottom());
        try { super.extractWidgetRenderState(g, mouseX, mouseY, partialTick); }
        finally { g.disableScissor(); }
    }
}
