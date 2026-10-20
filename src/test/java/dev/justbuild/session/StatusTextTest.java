package dev.justbuild.session;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.provider.ProgressListener.Phase;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StatusTextTest {
    @Test
    void formatsElapsedTime() {
        assertEquals("0:05", StatusText.elapsed(Duration.ofMillis(5_400)));
        assertEquals("1:02", StatusText.elapsed(Duration.ofSeconds(62)));
        assertEquals("12:00", StatusText.elapsed(Duration.ofMinutes(12)));
    }

    @Test
    void describesWhatTheAiIsDoing() {
        assertEquals("Codex CLI is planning", StatusText.activity("Codex CLI", Phase.WAITING, 0));
        assertEquals("Claude CLI is thinking", StatusText.activity("Claude CLI", Phase.THINKING, 0));
        assertEquals("Claude CLI is writing the plan", StatusText.activity("Claude CLI", Phase.WRITING, 0));
        assertEquals("Claude CLI is writing the plan, 1 operation so far",
                StatusText.activity("Claude CLI", Phase.WRITING, 1));
        assertEquals("Claude CLI is writing the plan, 14 operations so far",
                StatusText.activity("Claude CLI", Phase.WRITING, 14));
    }

    @Test
    void reviewRoundsReplaceThePhaseText() {
        assertEquals("Claude CLI is reviewing its plan (round 1 of 2), 7 operations so far",
                StatusText.activity("Claude CLI", Phase.WRITING, 7, 1, 2));
        assertEquals("Codex CLI is reviewing its plan (round 2 of 2)", StatusText.activity("Codex CLI", Phase.WAITING, 0, 2, 2));
        assertEquals("Codex CLI is planning", StatusText.activity("Codex CLI", Phase.WAITING, 0, 0, 0));
    }

    @Test
    void headlinesFollowTheActivity() {
        assertEquals("Codex CLI is thinking: Choosing materials",
                StatusText.activity("Codex CLI", Phase.THINKING, 0, 0, 0, false, "Choosing materials"));
        assertEquals("Codex CLI is reviewing its plan (round 1 of 1): Checking the roof, 4 operations so far",
                StatusText.activity("Codex CLI", Phase.WRITING, 4, 1, 1, false, "Checking the roof"));
        assertEquals("Codex CLI is planning", StatusText.activity("Codex CLI", Phase.WAITING, 0, 0, 0, false, " "));
    }

    @Test
    void aiSummaries() {
        assertEquals("AI 0:48, 23.4k tokens in, 1.9k out over 3 calls",
                StatusText.aiSummary(Duration.ofSeconds(48), 23_400, 1_900, 3));
        assertEquals("AI 0:12, 812 tokens in, 96 out", StatusText.aiSummary(Duration.ofSeconds(12), 812, 96, 1));
        assertEquals("AI 1:05", StatusText.aiSummary(Duration.ofSeconds(65), 0, 0, 0));
    }

    @Test
    void shrinkingHasItsOwnText() {
        assertEquals("Claude CLI is making its plan smaller (try 1 of 2), 3 operations so far",
                StatusText.activity("Claude CLI", Phase.WRITING, 3, 1, 2, true));
    }

    @Test
    void heartbeatAndActionBarLines() {
        assertEquals("Still working (0:30): Claude CLI is writing the plan, 14 operations so far.",
                StatusText.heartbeat("Claude CLI", Phase.WRITING, 14, Duration.ofSeconds(30)));
        assertEquals("JustBuild · Codex CLI is planning · 0:07",
                StatusText.waitingBar("Codex CLI", Phase.WAITING, 0, Duration.ofSeconds(7)));
        assertEquals("JustBuild · Placing “Hut” 62% (290/461)", StatusText.placingBar("Hut", 290, 461, false));
        assertEquals("JustBuild · Finishing “Hut” (connecting stairs and fences)", StatusText.placingBar("Hut", 461, 461, true));
    }

    @Test
    void placementMilestonesOnlyAtQuarterMarks() {
        assertEquals(25, StatusText.milestone(260, 1000, 25));
        assertEquals(-1, StatusText.milestone(240, 1000, 25));
        assertEquals(75, StatusText.milestone(800, 1000, 50));
        assertEquals(-1, StatusText.milestone(1000, 1000, 100));
    }
}
