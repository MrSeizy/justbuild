package dev.justbuild.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Game → server: what the player's AI is doing (ProgressListener.Phase ordinal), operations written so far, and its
 * latest headline (empty if none).
 */
public record AiProgressPayload(int id, int phase, int operations, String note) implements CustomPacketPayload {
    public static final Type<AiProgressPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "ai_progress"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AiProgressPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AiProgressPayload::id, ByteBufCodecs.VAR_INT, AiProgressPayload::phase,
            ByteBufCodecs.VAR_INT, AiProgressPayload::operations, ByteBufCodecs.stringUtf8(200), AiProgressPayload::note,
            AiProgressPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
