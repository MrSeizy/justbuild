package dev.justbuild.session;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.prompt.Exchange;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationTest {
    final Instant t0 = Instant.parse("2026-10-11T12:00:00Z");

    @Test
    void questionAndAnswerFlow() {
        Conversation c = new Conversation("a castle");
        assertEquals(3, c.questionsRemaining(3));
        c.ask("How big?", List.of("Small", "Large"), t0);
        assertTrue(c.hasPendingQuestion());
        assertEquals(List.of("Small", "Large"), c.pendingOptions());
        assertEquals(2, c.questionsRemaining(3));
        c.answer("  Large ");
        assertFalse(c.hasPendingQuestion());
        assertEquals(List.of(new Exchange("How big?", "Large")), c.exchanges());
        assertEquals(2, c.questionsRemaining(3));
        assertEquals(0, c.questionsRemaining(1));
    }

    @Test
    void pendingQuestionExpiresAfterTenMinutes() {
        Conversation c = new Conversation("x");
        assertFalse(c.isExpired(t0.plus(Duration.ofHours(5))));
        c.ask("Q?", List.of(), t0);
        assertFalse(c.isExpired(t0.plus(Duration.ofMinutes(10))));
        assertTrue(c.isExpired(t0.plus(Duration.ofMinutes(10)).plusSeconds(1)));
    }

    @Test
    void answeringWithoutQuestionIsAnError() {
        assertThrows(IllegalStateException.class, () -> new Conversation("x").answer("y"));
    }
}
