package dev.justbuild.mixin.common;

import java.util.Date;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** One cached login: the name and UUID, valid until a month after the player's latest join. */
@Mixin(targets = "net.minecraft.server.players.CachedUserNameToIdResolver$GameProfileInfo")
public interface NameCacheEntryAccessor {
    @Accessor("nameAndId") NameAndId justbuild$nameAndId();
    @Accessor("expirationDate") Date justbuild$expirationDate();
}
