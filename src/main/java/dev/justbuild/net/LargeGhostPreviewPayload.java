package dev.justbuild.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A ghost preview past {@link GhostPreviewPayload#MAX_CELLS}, on its own channel: a client that does not register it
 * (an older version, which would refuse the cells and be disconnected) gets the capped preview instead.
 */
public record LargeGhostPreviewPayload(GhostPreviewPayload preview) implements CustomPacketPayload {
    /** About 10 bytes a cell, plus the palette. */
    public static final int MAX_BYTES = 12 * 1024 * 1024;
    public static final Type<LargeGhostPreviewPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("justbuild", "ghost_preview_large"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LargeGhostPreviewPayload> CODEC =
            GhostPreviewPayload.codec(GhostPreviewPayload.LARGE_MAX_CELLS).map(LargeGhostPreviewPayload::new,
                    LargeGhostPreviewPayload::preview);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
