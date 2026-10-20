package dev.justbuild.net;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server → client: the answer to a ModelsRequestPayload with the same id; {@code error} is empty on success. */
public record ModelsPayload(int id, List<String> models, String error) implements CustomPacketPayload {
    public static final int MAX_MODELS = 1000;
    public static final Type<ModelsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "models"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ModelsPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ModelsPayload::id,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MAX_MODELS)), ModelsPayload::models,
            ByteBufCodecs.STRING_UTF8, ModelsPayload::error,
            ModelsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
