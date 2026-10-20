package dev.justbuild.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Form drafts belong to each screen; rebuilding their widgets should not move the insertion point. */
abstract class FormScreen extends Screen {
    protected FormScreen(Component title) { super(title); }

    @Override protected void rebuildWidgets() {
        FormFocus focus = FormFocus.capture(this);
        super.rebuildWidgets();
        focus.restore(this);
    }

    @Override public void resize(int width, int height) {
        FormFocus focus = FormFocus.capture(this);
        super.resize(width, height);
        focus.restore(this);
    }
}
