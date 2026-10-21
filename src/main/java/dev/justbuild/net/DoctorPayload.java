package dev.justbuild.net;

import dev.justbuild.config.JustBuildConfig;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** One diagnostic result; index -1 introduces the report and its expected result count. */
public record DoctorPayload(long id, int index, int total, String message, boolean error) implements CustomPacketPayload {
    public static final int MAX_MESSAGE = 8192;
    public static final Type<DoctorPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "doctor"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DoctorPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, DoctorPayload::id, ByteBufCodecs.VAR_INT, DoctorPayload::index,
            ByteBufCodecs.VAR_INT, DoctorPayload::total, ByteBufCodecs.stringUtf8(MAX_MESSAGE), DoctorPayload::message,
            ByteBufCodecs.BOOL, DoctorPayload::error, DoctorPayload::new);
    public DoctorPayload {
        if (total < 0 || total > JustBuildConfig.MAX_PROFILES || index < -1 || index >= total)
            throw new IllegalArgumentException("Invalid doctor result index");
        if (message.length() > MAX_MESSAGE) {
            int end = MAX_MESSAGE - 1;
            if (Character.isHighSurrogate(message.charAt(end - 1))) end--;
            message = message.substring(0, end) + "…";
        }
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
