package dev.justbuild.net;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ConstructionAssistPayload(UUID project, BlockPos pos) implements CustomPacketPayload {
    public static final Type<ConstructionAssistPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "construction_assist"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConstructionAssistPayload> CODEC = new StreamCodec<>() {
        public ConstructionAssistPayload decode(RegistryFriendlyByteBuf b) { return new ConstructionAssistPayload(b.readUUID(), b.readBlockPos()); }
        public void encode(RegistryFriendlyByteBuf b, ConstructionAssistPayload p) { b.writeUUID(p.project()); b.writeBlockPos(p.pos()); }
    };
    @Override public Type<ConstructionAssistPayload> type() { return TYPE; }
}
