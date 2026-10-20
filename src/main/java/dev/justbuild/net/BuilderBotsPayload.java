package dev.justbuild.net;

import dev.justbuild.world.BuildRegion;
import dev.justbuild.world.BuilderWorksite;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/** A bounded snapshot of nearby cosmetic builders, refreshed while work or a preview is active. */
public record BuilderBotsPayload(List<BuilderWorksite> sites) implements CustomPacketPayload {
    public static final int MAX_SITES = 16;
    public static final Type<BuilderBotsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "builder_bots"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuilderBotsPayload> CODEC = new StreamCodec<>() {
        @Override public BuilderBotsPayload decode(RegistryFriendlyByteBuf buffer) {
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_SITES) throw new IllegalArgumentException("Too many builder worksites");
            List<BuilderWorksite> sites = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String id = buffer.readUtf(64);
                var dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(buffer.readUtf(256)));
                var area = new BuildRegion(dimension, buffer.readBlockPos(), buffer.readBlockPos());
                sites.add(new BuilderWorksite(id, area, buffer.readBoolean()));
            }
            return new BuilderBotsPayload(sites);
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, BuilderBotsPayload value) {
            buffer.writeVarInt(value.sites().size());
            for (BuilderWorksite site : value.sites()) {
                buffer.writeUtf(site.id(), 64);
                buffer.writeUtf(site.area().dimension().identifier().toString(), 256);
                buffer.writeBlockPos(site.area().min()); buffer.writeBlockPos(site.area().max());
                buffer.writeBoolean(site.working());
            }
        }
    };

    public BuilderBotsPayload {
        sites = List.copyOf(sites);
        if (sites.size() > MAX_SITES || new HashSet<>(sites.stream().map(BuilderWorksite::id).toList()).size() != sites.size()) {
            throw new IllegalArgumentException("Too many or duplicate builder worksites");
        }
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
