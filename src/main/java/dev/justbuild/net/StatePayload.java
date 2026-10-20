package dev.justbuild.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server → client: the panel's state as one JSON string (see session.StateJson). */
public record StatePayload(String json) implements CustomPacketPayload {
    public static final int MAX_BYTES = 1024 * 1024;
    public static final Type<StatePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.stringUtf8(262144), StatePayload::json, StatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
