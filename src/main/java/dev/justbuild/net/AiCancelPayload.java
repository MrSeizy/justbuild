package dev.justbuild.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server → game: stop working on that request (cancelled, timed out, or replaced). */
public record AiCancelPayload(int id) implements CustomPacketPayload {
    public static final Type<AiCancelPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "ai_cancel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AiCancelPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, AiCancelPayload::id, AiCancelPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
