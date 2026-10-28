package dev.justbuild.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Additive protocol: existing preview and builder packets retain their released wire formats. */
public record ConstructionStatePayload(String json) implements CustomPacketPayload {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final Type<ConstructionStatePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "construction_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConstructionStatePayload> CODEC = new StreamCodec<>() {
        public ConstructionStatePayload decode(RegistryFriendlyByteBuf b) { return new ConstructionStatePayload(b.readUtf(1024 * 1024)); }
        public void encode(RegistryFriendlyByteBuf b, ConstructionStatePayload p) { b.writeUtf(p.json(), 1024 * 1024); }
    };
    @Override public Type<ConstructionStatePayload> type() { return TYPE; }
}
