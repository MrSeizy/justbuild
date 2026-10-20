package dev.justbuild.mixin.client;

import net.minecraft.client.gui.components.MultilineTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Selection anchor needed to keep both ends of a multiline selection through resize. */
@Mixin(MultilineTextField.class)
public interface MultilineTextFieldAccessor {
    @Accessor("selectCursor") int justbuild$selectionAnchor();
}
