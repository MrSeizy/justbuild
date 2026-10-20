package dev.justbuild.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Game → server: whether this player's own AI can plan builds (relay.RelayJson.Hello as JSON). */
public record ClientAiPayload(String json) implements CustomPacketPayload {
    public static final Type<ClientAiPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "client_ai"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientAiPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.stringUtf8(1024), ClientAiPayload::json, ClientAiPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
