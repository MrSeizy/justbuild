package dev.justbuild.client;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

/** Restores a form's focused field and insertion point after a resize recreates its widgets. */
record FormFocus(String label, int cursor) {
    static FormFocus capture(Screen screen) {
        return screen.getFocused() instanceof EditBox field
                ? new FormFocus(field.getMessage().getString(), field.getCursorPosition()) : new FormFocus(null, 0);
    }

    void restore(Screen screen) {
        if (label == null) return;
        for (var child : screen.children()) {
            if (child instanceof EditBox field && field.getMessage().getString().equals(label)) {
                screen.setFocused(field);
                field.moveCursorTo(cursor, false);
                return;
            }
        }
    }
}
