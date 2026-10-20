package dev.justbuild.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client → server: list the models for one profile as currently edited (a ProfileEdits.Edit as JSON). */
public record ModelsRequestPayload(int id, String json) implements CustomPacketPayload {
    public static final Type<ModelsRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("justbuild", "models_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ModelsRequestPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ModelsRequestPayload::id, ByteBufCodecs.STRING_UTF8, ModelsRequestPayload::json,
            ModelsRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
