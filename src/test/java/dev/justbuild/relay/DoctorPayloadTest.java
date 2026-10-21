package dev.justbuild.relay;

import static org.junit.jupiter.api.Assertions.*;
import dev.justbuild.net.DoctorPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class DoctorPayloadTest {
    @Test void boundsLargeUnicodeMessagesAndKeepsRequestIdentity() {
        var result = new DoctorPayload(12345L, 19, 20, "界".repeat(9000), true);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            DoctorPayload.CODEC.encode(buffer, result);
            assertTrue(buffer.readableBytes() < 32767);
            assertEquals(result, DoctorPayload.CODEC.decode(buffer));
            assertTrue(result.message().endsWith("…"));
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
        String astral = "🛠".repeat(5000);
        String clipped = new DoctorPayload(1, 0, 1, astral, false).message();
        assertFalse(Character.isHighSurrogate(clipped.charAt(clipped.length() - 2)));
    }

    @Test void rejectsUnboundedCountsAndOutOfRangeResults() {
        assertThrows(IllegalArgumentException.class, () -> new DoctorPayload(1, -1, 21, "", false));
        assertThrows(IllegalArgumentException.class, () -> new DoctorPayload(1, -2, 1, "", false));
        assertThrows(IllegalArgumentException.class, () -> new DoctorPayload(1, 20, 20, "", false));
        assertThrows(IllegalArgumentException.class, () -> new DoctorPayload(1, 0, 0, "", false));
        assertDoesNotThrow(() -> new DoctorPayload(1, -1, 0, "Permission denied", true));
    }
}
