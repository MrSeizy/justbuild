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

    @Test void critiqueAndRepairStagesAreTracked() {
        RequestStatus status = new RequestStatus("Claude CLI", java.time.Instant.now());
        status.critiquing(1, 2);
        assertEquals(ReviewRounds.Stage.CRITIQUE, status.stage());
        assertEquals(1, status.reviewsStarted());
        status.repairing(1, 2);
        assertEquals(ReviewRounds.Stage.REPAIR, status.stage());
        status.reviewing(1, 1);
        assertEquals(ReviewRounds.Stage.REVIEW, status.stage(), "self-review and the quick correction keep their stage");
        status.shrinking(1, 2);
        assertNull(status.stage());
    }

    @Test void reviewsStartedNeverGoesBackwards() {
        RequestStatus status = new RequestStatus("Claude CLI", Instant.EPOCH);
        status.critiquing(2, 2);
        status.repairing(1, 2);
        assertEquals(2, status.reviewsStarted());
    }
}
