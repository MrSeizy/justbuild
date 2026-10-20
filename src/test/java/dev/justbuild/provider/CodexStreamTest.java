package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class CodexStreamTest {
    /** Shapes from `codex exec --json` (codex-cli 0.162.1), shortened. */
    static final List<String> EVENTS = List.of(
            "{\"type\":\"thread.started\",\"thread_id\":\"t\"}",
            "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"error\",\"message\":\"Code Mode is unavailable\"}}",
            "{\"type\":\"turn.started\"}",
            "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_1\",\"type\":\"reasoning\",\"text\":\"**Choosing materials**\\n\\nStone bricks for the base…\"}}",
            "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_2\",\"type\":\"reasoning\",\"text\":\"Plain text without a heading that goes on and on about the roof and the windows and the lighting\"}}",
            "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_3\",\"type\":\"agent_message\",\"text\":\"{\\\"kind\\\":\\\"build\\\",\\\"operations\\\":[{\\\"op\\\":\\\"set\\\"},{\\\"op\\\":\\\"fill\\\"}]}\"}}",
            "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":21840,\"cached_input_tokens\":10752,\"output_tokens\":309,\"reasoning_output_tokens\":73}}",
            "not json");

    @Test
    void reportsHeadlinesOperationsAndTokens() {
        RecordingListener progress = new RecordingListener();
        CodexStream stream = new CodexStream(progress);
        EVENTS.forEach(stream::accept);
        assertEquals(List.of(ProgressListener.Phase.THINKING, ProgressListener.Phase.THINKING,
                ProgressListener.Phase.WRITING), progress.phases);
        assertEquals("Choosing materials", progress.notes.get(0));
        assertTrue(progress.notes.get(1).startsWith("Plain text without a heading"));
        assertTrue(progress.notes.get(1).length() <= CodexStream.MAX_NOTE + 1);
        assertEquals(List.of(2), progress.ops);
        assertEquals(1, progress.tokens.size());
        assertArrayEquals(new long[] {21840, 309}, progress.tokens.get(0));
    }

    @Test
    void remembersAFailedTurn() {
        CodexStream stream = new CodexStream(ProgressListener.NONE);
        stream.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"usage limit reached\"}}");
        assertEquals("usage limit reached", stream.failure());
    }
}
