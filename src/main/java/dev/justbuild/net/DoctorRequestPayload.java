package dev.justbuild.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Ask for saved server-profile diagnostics, tied to one results dialog. */
public record DoctorRequestPayload(long id) implements CustomPacketPayload {
    public static final Type<DoctorRequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "doctor_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DoctorRequestPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, DoctorRequestPayload::id, DoctorRequestPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
