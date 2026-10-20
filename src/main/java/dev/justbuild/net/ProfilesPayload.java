package dev.justbuild.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client → server: the profile editor's whole list (config.ProfileEdits JSON). A packet rather than a command because
 * this can carry an API key and command text ends up in logs (Fabric's client command handling writes it to debug.log).
 */
public record ProfilesPayload(String json) implements CustomPacketPayload {
    /** Serverbound custom payloads are capped at 32767 bytes. */
    public static final int MAX_LENGTH = 30_000;
    public static final Type<ProfilesPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "profiles"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ProfilesPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ProfilesPayload::json, ProfilesPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
