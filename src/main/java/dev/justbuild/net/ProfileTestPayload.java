package dev.justbuild.net;

import dev.justbuild.provider.ProfileDiagnostics.Check;
import dev.justbuild.provider.ProfileDiagnostics.Status;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Fixed diagnostic descriptions only: no keys, URLs or raw provider responses. */
public record ProfileTestPayload(long id, List<Check> checks) implements CustomPacketPayload {
    public static final Type<ProfileTestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "profile_test"));
    private static final StreamCodec<RegistryFriendlyByteBuf, Check> CHECK = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(4).map(Status::valueOf, Status::name), Check::status,
            ByteBufCodecs.stringUtf8(40), Check::title, ByteBufCodecs.stringUtf8(240), Check::detail, Check::new);
    public static final StreamCodec<RegistryFriendlyByteBuf, ProfileTestPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, ProfileTestPayload::id, CHECK.apply(ByteBufCodecs.list(4)), ProfileTestPayload::checks, ProfileTestPayload::new);
    public ProfileTestPayload {
        checks = List.copyOf(checks);
        if (checks.size() > 4) throw new IllegalArgumentException("Too many checks");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
