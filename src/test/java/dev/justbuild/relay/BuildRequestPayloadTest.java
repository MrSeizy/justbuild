package dev.justbuild.relay;

import static org.junit.jupiter.api.Assertions.*;
import dev.justbuild.net.BuildRequestPayload;
import dev.justbuild.prompt.AlternativeRequests;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BuildRequestPayloadTest {
    @Test void longMultilineRequestsAndExplicitAlternativesKeepExactConsent() {
        String text = "A cottage\n" + "oak beams and a stone foundation. ".repeat(100).stripTrailing();
        for (String action : List.of("build", "edit")) for (int count : List.of(1, 2, 3)) {
            var payload = new BuildRequestPayload(action, action.equals("edit") ? "0123abcd" : "", count, text, "");
            var typed = TypedRequests.of(payload.consentCommand());
            assertEquals(count, typed.requests().size());
            for (int i = 1; i <= count; i++) assertEquals(count == 1 ? text : AlternativeRequests.variant(text, i, count), typed.requests().get(i - 1));
            var gate = new RelayGate(); gate.command(payload.consentCommand(), Instant.EPOCH);
            for (int i = 0; i < Math.min(count * RelayGate.CALLS, RelayGate.HOURLY); i++) assertTrue(gate.allow(Instant.EPOCH));
            assertFalse(gate.allow(Instant.EPOCH)); gate.reset(); assertFalse(gate.allow(Instant.EPOCH));
            var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
            try { BuildRequestPayload.CODEC.encode(buf, payload); assertTrue(buf.readableBytes() < 32767); assertEquals(payload, BuildRequestPayload.CODEC.decode(buf)); }
            finally { buf.release(); }
        }
        var answer = new BuildRequestPayload("answer", "", 1, text, BuildRequestPayload.questionKey("How big?"));
        assertEquals(text, TypedRequests.of(answer.consentCommand()).answer());
    }

    @Test void rejectsInvalidTargetsActionsAndSizesBeforeDispatch() {
        assertThrows(IllegalArgumentException.class, () -> new BuildRequestPayload("op", "", 1, "player", ""));
        assertThrows(IllegalArgumentException.class, () -> new BuildRequestPayload("edit", "", 1, "roof", ""));
        assertThrows(IllegalArgumentException.class, () -> new BuildRequestPayload("edit", "#1234567", 1, "roof", ""));
        assertThrows(IllegalArgumentException.class, () -> new BuildRequestPayload("build", "12345678", 1, "roof", ""));
        assertThrows(IllegalArgumentException.class, () -> new BuildRequestPayload("build", "", 4, "roof", ""));
        assertThrows(IllegalArgumentException.class, () -> new BuildRequestPayload("build", "", 1, "x".repeat(4097), ""));
        assertThrows(IllegalArgumentException.class, () -> new BuildRequestPayload("build", "", 1, "a\0b", ""));
        assertThrows(IllegalArgumentException.class, () -> new BuildRequestPayload("answer", "", 1, "yes", ""));
        assertDoesNotThrow(() -> new BuildRequestPayload("build", "", 1, "界".repeat(4096), ""));
    }

    @Test void longerTextStillRequiresLocalOwnership() {
        dev.justbuild.world.McBootstrap.init();
        String text = "a cottage\n" + "wooden details ".repeat(240);
        var spec = PromptCheckTest.spec(text, List.of());
        assertNull(PromptCheck.check(spec, new PromptCheck.Seen(Set.of(text), Set.of(), Set.of())).problem());
        assertNotNull(PromptCheck.check(spec, new PromptCheck.Seen(Set.of("a cottage"), Set.of(), Set.of())).problem());
        String option = AlternativeRequests.variant(text, 2, 3);
        assertNull(PromptCheck.check(PromptCheckTest.spec(option, List.of()), new PromptCheck.Seen(Set.of(option), Set.of(), Set.of())).problem());
        assertNotNull(PromptCheck.check(PromptCheckTest.spec(option, List.of()), new PromptCheck.Seen(Set.of(text), Set.of(), Set.of())).problem());
    }

    @Test void revisingAPreviewIsOneTypedRequestWithoutATarget() {
        dev.justbuild.world.McBootstrap.init();
        var payload = new BuildRequestPayload("revise", "", 1, "make the roof steeper", "");
        assertEquals("justbuild revise make the roof steeper", payload.consentCommand());
        assertEquals(List.of("make the roof steeper"), TypedRequests.of(payload.consentCommand()).requests());
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try { BuildRequestPayload.CODEC.encode(buf, payload); assertEquals(payload, BuildRequestPayload.CODEC.decode(buf)); }
        finally { buf.release(); }
        assertThrows(IllegalArgumentException.class, () -> new BuildRequestPayload("revise", "0123abcd", 1, "roof", ""));
        assertThrows(IllegalArgumentException.class, () -> new BuildRequestPayload("revise", "", 2, "roof", ""));
        assertThrows(IllegalArgumentException.class, () -> new BuildRequestPayload("revise", "", 1, "roof",
                BuildRequestPayload.questionKey("How big?")));
    }
}
