package dev.justbuild.mixin.common;

import java.util.Map;
import net.minecraft.server.players.CachedUserNameToIdResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla's lookup by name asks Mojang on a miss; reading the cache keeps invites local. Keys are lowercase names. */
@Mixin(CachedUserNameToIdResolver.class)
public interface NameCacheAccessor {
    @Accessor("profilesByName") Map<String, Object> justbuild$profilesByName();
}
