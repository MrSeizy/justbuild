package dev.justbuild.relay;

import static org.junit.jupiter.api.Assertions.*;
import dev.justbuild.net.BuilderBotsPayload;
import dev.justbuild.world.BuildRegion;
import dev.justbuild.world.BuilderWorksite;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class BuilderBotsPayloadTest {
    static { dev.justbuild.world.McBootstrap.init(); }
    private static BuilderWorksite site(String id) {
        return new BuilderWorksite(id, new BuildRegion(Level.OVERWORLD, new BlockPos(-5, 64, -5), new BlockPos(5, 64, 5)), true);
    }

    @Test void boundedSnapshotRoundTripsWithoutPromptOrProfileData() {
        var sites = IntStream.range(0, BuilderBotsPayload.MAX_SITES).mapToObj(i -> site("worker-" + i)).toList();
        var expected = new BuilderBotsPayload(sites);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            BuilderBotsPayload.CODEC.encode(buffer, expected);
            assertTrue(buffer.readableBytes() < 2048);
            assertEquals(expected, BuilderBotsPayload.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }

    @Test void rejectsOversizedDuplicateAndMalformedSites() {
        assertThrows(IllegalArgumentException.class, () -> new BuilderBotsPayload(List.of(site("one"), site("one"))));
        assertThrows(IllegalArgumentException.class, () -> new BuilderBotsPayload(IntStream.range(0, 17).mapToObj(i -> site("b" + i)).toList()));
        assertThrows(IllegalArgumentException.class, () -> site("not an identifier"));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            for (int count : List.of(-1, 17, Integer.MAX_VALUE)) {
                buffer.clear(); buffer.writeVarInt(count);
                assertThrows(IllegalArgumentException.class, () -> BuilderBotsPayload.CODEC.decode(buffer));
            }
            buffer.clear(); buffer.writeVarInt(1); buffer.writeUtf("one"); buffer.writeUtf("minecraft:overworld");
            buffer.writeBlockPos(BlockPos.ZERO); buffer.writeBlockPos(new BlockPos(10000, 0, 0)); buffer.writeBoolean(true);
            assertThrows(IllegalArgumentException.class, () -> BuilderBotsPayload.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void visibilityDistanceMeasuresTheFootprintRatherThanItsCenter() {
        var site = site("one");
        assertEquals(0, site.distanceSquared(new Vec3(5, 65, -5)));
        assertEquals(100, site.distanceSquared(new Vec3(16, 65, 0)));
        assertEquals(100, site.distanceSquared(new Vec3(0, 76, 0)));
        assertEquals(0, new BuilderBotsPayload(List.of()).sites().size());
    }
}
