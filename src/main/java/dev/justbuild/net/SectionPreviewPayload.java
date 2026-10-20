package dev.justbuild.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Full proposed building for client-only section views; state palette never contains block entity data. */
public record SectionPreviewPayload(GhostPreviewPayload model, GhostPreviewPayload before) implements CustomPacketPayload {
    public static final int MAX_BYTES = GhostPreviewPayload.MAX_BYTES * 2;
    public SectionPreviewPayload(GhostPreviewPayload model) {
        this(model, new GhostPreviewPayload(model.dimension(), java.util.List.of(), java.util.List.of(), 0));
    }
    public static final Type<SectionPreviewPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "section_preview"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SectionPreviewPayload> CODEC = new StreamCodec<>() {
        @Override public SectionPreviewPayload decode(RegistryFriendlyByteBuf buf) {
            return new SectionPreviewPayload(GhostPreviewPayload.CODEC.decode(buf), GhostPreviewPayload.CODEC.decode(buf));
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, SectionPreviewPayload payload) {
            GhostPreviewPayload.CODEC.encode(buf, payload.model()); GhostPreviewPayload.CODEC.encode(buf, payload.before());
        }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
