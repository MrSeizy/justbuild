package dev.justbuild.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** A section preview whose models may pass {@link GhostPreviewPayload#MAX_CELLS}; see {@link LargeGhostPreviewPayload}. */
public record LargeSectionPreviewPayload(SectionPreviewPayload section) implements CustomPacketPayload {
    public static final int MAX_BYTES = LargeGhostPreviewPayload.MAX_BYTES * 2;
    public static final Type<LargeSectionPreviewPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("justbuild", "section_preview_large"));
    private static final StreamCodec<RegistryFriendlyByteBuf, GhostPreviewPayload> MODEL =
            GhostPreviewPayload.codec(GhostPreviewPayload.LARGE_MAX_CELLS);
    public static final StreamCodec<RegistryFriendlyByteBuf, LargeSectionPreviewPayload> CODEC = new StreamCodec<>() {
        @Override public LargeSectionPreviewPayload decode(RegistryFriendlyByteBuf buf) {
            return new LargeSectionPreviewPayload(new SectionPreviewPayload(MODEL.decode(buf), MODEL.decode(buf)));
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, LargeSectionPreviewPayload payload) {
            MODEL.encode(buf, payload.section().model()); MODEL.encode(buf, payload.section().before());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
