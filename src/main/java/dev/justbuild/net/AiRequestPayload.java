package dev.justbuild.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server → game: plan this with your own AI (relay.RelayJson.Request as JSON). Large: an edit carries the whole plan. */
public record AiRequestPayload(int id, String json) implements CustomPacketPayload {
    public static final int MAX_CHARS = 1_500_000;
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final Type<AiRequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "ai_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AiRequestPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AiRequestPayload::id, ByteBufCodecs.stringUtf8(MAX_CHARS), AiRequestPayload::json,
            AiRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
