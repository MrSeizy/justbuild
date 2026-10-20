package dev.justbuild.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Game → server: the plan text from the player's AI, or an error (then {@code text} is empty), and the tokens it
 * used. Large.
 */
public record AiResponsePayload(int id, String text, String error, long tokensIn, long tokensOut)
        implements CustomPacketPayload {
    public static final int MAX_CHARS = 2_000_000;
    public static final int MAX_BYTES = 6 * 1024 * 1024;
    public static final Type<AiResponsePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "ai_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AiResponsePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AiResponsePayload::id, ByteBufCodecs.stringUtf8(MAX_CHARS), AiResponsePayload::text,
            ByteBufCodecs.stringUtf8(1000), AiResponsePayload::error, ByteBufCodecs.VAR_LONG, AiResponsePayload::tokensIn,
            ByteBufCodecs.VAR_LONG, AiResponsePayload::tokensOut, AiResponsePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
