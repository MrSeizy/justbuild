package dev.justbuild.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/**
 * A rounded glass button. It is still a {@link Button}, so {@code active}, tooltips, keyboard navigation, narration,
 * and the game tests' click-by-label work as for vanilla buttons; only the drawing differs.
 */
final class GlassButton extends Button {
    enum Role { NORMAL, PRIMARY, CONFIRM, DANGER, GHOST, TAB, CHOICE }

    private Role role = Role.NORMAL;
    /** The current tab or choice: drawn lit with full-strength text; pressing it does nothing. */
    private boolean selected;
    private Identifier icon;
    private String subtitle;

    private GlassButton(int x, int y, int width, int height, Component message, OnPress onPress, CreateNarration narration) {
        super(x, y, width, height, message, onPress, narration);
    }

    public static Builder builder(Component message, OnPress onPress) {
        return new Builder(message, onPress);
    }

    GlassButton primary() {
        role = Role.PRIMARY;
        return this;
    }

    GlassButton danger() {
        role = Role.DANGER;
        return this;
    }

    GlassButton choice() { role = Role.CHOICE; return this; }

    /**
     * The current choice in a list: drawn lit with normal text. It stays active, unlike a disabled button, so Tab reaches
     * it and a screen reader says it is selected; pressing it does nothing.
     */
    GlassButton current() {
        selected = true;
        return this;
    }

    /** One tab of a segmented row, with its 9×9 icon; the current tab looks and narrates as selected. */
    GlassButton tab(Identifier icon, boolean current) {
        role = Role.TAB;
        this.icon = icon;
        return current ? current() : this;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (!selected) super.onPress(input);
    }

    /** "History tab" like vanilla's tabs, and "Selected: World tab" for the current choice. */
    @Override
    protected MutableComponent createNarrationMessage() {
        MutableComponent message = role == Role.TAB ? Component.translatable("gui.narrate.tab", getMessage())
                : super.createNarrationMessage();
        if (subtitle != null) message.append(". " + subtitle);
        return selected ? Component.translatable("narrator.select", message) : message;
    }

    /** The current choice skips vanilla's "press Enter to activate" hint, since pressing it does nothing. */
    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        if (selected) output.add(NarratedElementType.TITLE, createNarrationMessage());
        else super.updateWidgetNarration(output);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        boolean lit = selected || active && isHovered();
        boolean dim = !active && !selected;
        int fill = switch (role) {
            case PRIMARY -> lit ? 0x5234D399 : 0x2E34D399;
            case CONFIRM -> lit ? 0x5222C55E : 0x2E22C55E;
            case DANGER -> lit ? 0x52EF4444 : 0x2EEF4444;
            case GHOST -> lit ? 0x24FFFFFF : 0;
            case TAB -> lit ? 0x1AFFFFFF : 0;
            case NORMAL -> lit ? 0x24FFFFFF : 0x12FFFFFF;
            case CHOICE -> selected ? 0xFF574728 : lit ? 0xFF354154 : 0xFF252E3D;
        };
        if (subtitle != null && role == Role.NORMAL) fill = lit ? 0xFF354154 : 0xFF252E3D;
        if (dim) fill = ARGB.multiplyAlpha(fill, 0.45f);
        boolean keyboard = active && isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard();
        // A faint ring tells the current choice from a hovered one; the keyboard focus outline wins.
        int focus = switch (role) {
            case PRIMARY -> Glass.PRIMARY;
            case CONFIRM -> 0xFF22C55E;
            case CHOICE -> Glass.TITLE;
            default -> Glass.ACCENT;
        };
        int edge = keyboard ? focus : selected && role != Role.TAB ? 0x33FFFFFF : 0;
        if (!dim && (role == Role.CHOICE || subtitle != null)) edge = keyboard || selected ? Glass.TITLE : lit ? 0xFF8794A8 : 0xFF465166;
        Glass.pill(g, getX(), getY(), getWidth(), getHeight(), fill, edge);
        int color = dim ? Glass.DISABLED_TEXT : switch (role) {
            case PRIMARY -> Glass.PRIMARY_TEXT;
            case CONFIRM -> 0xFF9DEEB9;
            case DANGER -> Glass.DANGER_TEXT;
            case TAB -> lit ? 0xFFFFFFFF : Glass.MUTED;
            case NORMAL, GHOST -> Glass.TEXT;
            case CHOICE -> selected ? Glass.TITLE : Glass.TEXT;
        };
        var font = Minecraft.getInstance().font;
        if (subtitle != null) {
            int x = getX() + 10, y = getY() + (getHeight() - 21) / 2;
            g.text(font, font.plainSubstrByWidth(getMessage().getString(), getWidth() - 20), x, y, color, false);
            g.text(font, font.plainSubstrByWidth(subtitle, getWidth() - 20), x, y + 12,
                    dim ? Glass.DISABLED_TEXT : 0xFFBAC5D6, false);
            return;
        }
        Component label = getMessage().copy().withColor(color & 0xFFFFFF);
        int textWidth = font.width(label);
        if (icon == null || textWidth + 12 > getWidth() - 4) {
            extractScrollingStringOverContents(g.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE), label, 2);
            return;
        }
        int start = getX() + (getWidth() - 12 - textWidth) / 2;
        Glass.icon(g, icon, start, getY() + (getHeight() - 9) / 2, color);
        g.text(font, label, start + 12, getY() + (getHeight() - 8) / 2, color);
    }

    /**
     * Like {@link Button.Builder}, plus the role. It extends Button.Builder only so that {@code GlassButton.builder} can
     * hide {@code Button.builder}; the parent's fields are private and unused, so every setter is overridden here.
     * Recheck against Button.Builder after a Minecraft update.
     */
    static final class Builder extends Button.Builder {
        private final Component message;
        private final OnPress onPress;
        private int x;
        private int y;
        private int width = DEFAULT_WIDTH;
        private int height = DEFAULT_HEIGHT;
        private Tooltip tooltip;
        private CreateNarration narration = DEFAULT_NARRATION;
        private Role role = Role.NORMAL;
        private String subtitle;

        private Builder(Component message, OnPress onPress) {
            super(message, onPress);
            this.message = message;
            this.onPress = onPress;
        }

        @Override
        public Builder pos(int x, int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        @Override
        public Builder width(int width) {
            this.width = width;
            return this;
        }

        @Override
        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        @Override
        public Builder bounds(int x, int y, int width, int height) {
            return pos(x, y).size(width, height);
        }

        @Override
        public Builder tooltip(Tooltip tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        @Override
        public Builder createNarration(CreateNarration narration) {
            this.narration = narration;
            return this;
        }

        Builder primary() {
            role = Role.PRIMARY;
            return this;
        }

        Builder confirmation() {
            role = Role.CONFIRM;
            return this;
        }

        Builder danger() {
            role = Role.DANGER;
            return this;
        }

        Builder ghost() {
            role = Role.GHOST;
            return this;
        }

        Builder subtitle(String text) { subtitle = text; return this; }

        @Override
        public GlassButton build() {
            GlassButton button = new GlassButton(x, y, width, height, message, onPress, narration);
            button.role = role;
            button.subtitle = subtitle;
            button.setTooltip(tooltip); // as vanilla does; null means none
            return button;
        }
    }
}
