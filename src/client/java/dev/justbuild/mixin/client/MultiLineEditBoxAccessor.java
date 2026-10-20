package dev.justbuild.mixin.client;

import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla fixes the wrap width at construction; retain cursor/selection when rebuilding on resize. */
@Mixin(MultiLineEditBox.class)
public interface MultiLineEditBoxAccessor {
    @Accessor("textField") MultilineTextField justbuild$textField();
}
