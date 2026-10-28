package dev.justbuild.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ConstructionGhostPayload(GhostPreviewPayload preview) implements CustomPacketPayload {
    public static final Type<ConstructionGhostPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "construction_ghost"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConstructionGhostPayload> CODEC = GhostPreviewPayload.codec(GhostPreviewPayload.LARGE_MAX_CELLS)
            .map(ConstructionGhostPayload::new, ConstructionGhostPayload::preview);
    @Override public Type<ConstructionGhostPayload> type() { return TYPE; }
}
