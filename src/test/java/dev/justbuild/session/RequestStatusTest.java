package dev.justbuild.session;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RequestStatusTest {
    @Test void chatUpdatesWaitOneMinuteBetweenMessages() {
        var status = new RequestStatus("Codex CLI", Instant.EPOCH);
        assertFalse(status.heartbeatDue(Duration.ofSeconds(15)));
        assertFalse(status.heartbeatDue(Duration.ofSeconds(59)));
        assertTrue(status.heartbeatDue(Duration.ofSeconds(60)));
        assertFalse(status.heartbeatDue(Duration.ofSeconds(61)));
        assertFalse(status.heartbeatDue(Duration.ofSeconds(119)));
        assertTrue(status.heartbeatDue(Duration.ofSeconds(120)));
    }

    @Test void resumingAfterAPauseDoesNotReplayMissedChatUpdates() {
        var status = new RequestStatus("Codex CLI", Instant.EPOCH);
        assertTrue(status.heartbeatDue(Duration.ofSeconds(305)));
        assertFalse(status.heartbeatDue(Duration.ofSeconds(306)));
        assertFalse(status.heartbeatDue(Duration.ofSeconds(360)));
        assertFalse(status.heartbeatDue(Duration.ofSeconds(364)));
        assertTrue(status.heartbeatDue(Duration.ofSeconds(365)));
    }
}
