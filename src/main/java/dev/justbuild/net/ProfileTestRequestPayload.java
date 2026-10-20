package dev.justbuild.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** A draft can contain a newly typed key; it is only sent to the profile's owning server. */
public record ProfileTestRequestPayload(long id, String json, boolean cancel) implements CustomPacketPayload {
    public static final Type<ProfileTestRequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "profile_test_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ProfileTestRequestPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, ProfileTestRequestPayload::id, ByteBufCodecs.stringUtf8(8192), ProfileTestRequestPayload::json,
            ByteBufCodecs.BOOL, ProfileTestRequestPayload::cancel, ProfileTestRequestPayload::new);
    public ProfileTestRequestPayload {
        if (json == null || json.length() > 8192 || cancel && !json.isEmpty()) throw new IllegalArgumentException("Invalid profile test");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
