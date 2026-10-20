package dev.justbuild.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server → game: a plan waiting for [Build it] covers min..max (inclusive) in that dimension, or, with show false, the
 * preview is gone. Sent when it starts, once a second while it waits, and when it is built or cancelled.
 */
public record PreviewPayload(boolean show, String dimension, BlockPos min, BlockPos max, String title)
        implements CustomPacketPayload {
    public static final Type<PreviewPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "preview"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PreviewPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, PreviewPayload::show, ByteBufCodecs.stringUtf8(256), PreviewPayload::dimension,
            BlockPos.STREAM_CODEC, PreviewPayload::min, BlockPos.STREAM_CODEC, PreviewPayload::max,
            ByteBufCodecs.stringUtf8(256), PreviewPayload::title, PreviewPayload::new);

    public static PreviewPayload hidden() {
        return new PreviewPayload(false, "", BlockPos.ZERO, BlockPos.ZERO, "");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
