package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClaudeStreamTest {
    static final class Recorder implements ProgressListener {
        final List<Phase> phases = new ArrayList<>();
        final List<Integer> ops = new ArrayList<>();

        @Override
        public void phase(Phase phase) {
            phases.add(phase);
        }

        @Override
        public void operations(int count) {
            ops.add(count);
        }
    }

    static final java.time.Instant NOW = java.time.Instant.parse("2026-10-12T12:00:00Z");

    static String limitEvent(String status, double weekly, double fiveHour, long weeklyReset) {
        return "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"" + status + "\","
                + "\"rateLimitType\":\"seven_day\",\"utilization\":" + weekly + ",\"unifiedWindows\":{"
                + "\"five_hour\":{\"utilization\":" + fiveHour + ",\"resetsAt\":" + (NOW.getEpochSecond() + 7200) + "},"
                + "\"seven_day\":{\"utilization\":" + weekly + ",\"resetsAt\":" + weeklyReset + "}}}}";
    }

    @Test
    void warnsWhenThePlanIsNearItsLimit() {
        long reset = NOW.getEpochSecond() + 33 * 3600;
        var notice = ClaudeStream.limitNotice(com.google.gson.JsonParser.parseString(
                limitEvent("allowed_warning", 0.84, 0.02, reset)).getAsJsonObject().getAsJsonObject("rate_limit_info"),
                NOW).orElseThrow();
        assertEquals("claude-seven_day-80", notice[0]);
        assertEquals("Your Claude plan has used 84% of its weekly limit; it resets in 1 day 9 h.", notice[1]);
    }

    @Test
    void saysWhenTheLimitIsUsedUpAndStaysQuietBelowThreeQuarters() {
        var info = com.google.gson.JsonParser.parseString(limitEvent("rejected", 0.4, 1.0, 0)).getAsJsonObject()
                .getAsJsonObject("rate_limit_info");
        var notice = ClaudeStream.limitNotice(info, NOW).orElseThrow();
        assertEquals("Your Claude plan's 5-hour limit is used up; it resets in 2 h 0 min.", notice[1]);
        var calm = com.google.gson.JsonParser.parseString(limitEvent("allowed", 0.5, 0.1, 0)).getAsJsonObject()
                .getAsJsonObject("rate_limit_info");
        assertTrue(ClaudeStream.limitNotice(calm, NOW).isEmpty());
    }

    @Test
    void reportsTokensAndLimitNoticesFromTheStream() {
        RecordingListener progress = new RecordingListener();
        ClaudeStream stream = new ClaudeStream(progress);
        stream.accept(limitEvent("allowed", 0.91, 0.1, java.time.Instant.now().getEpochSecond() + 3600));
        stream.accept("{\"type\":\"result\",\"is_error\":false,\"usage\":{\"input_tokens\":2,"
                + "\"cache_creation_input_tokens\":1148,\"cache_read_input_tokens\":50,\"output_tokens\":174}}");
        assertEquals("claude-seven_day-90", progress.notices.get(0)[0]);
        assertArrayEquals(new long[] {1200, 174}, progress.tokens.get(0));
    }

    static String delta(String partial) {
        return "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":1,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":" + com.google.gson.JsonParser.parseString(
                        "\"" + partial.replace("\\", "\\\\").replace("\"", "\\\"") + "\"") + "}}}";
    }

    @Test
    void reportsPhasesAndCountsOperationsAcrossChunkBoundaries() {
        Recorder recorder = new Recorder();
        ClaudeStream stream = new ClaudeStream(recorder);
        stream.accept("{\"type\":\"system\",\"subtype\":\"init\"}");
        stream.accept("not json at all");
        stream.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}}");
        stream.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\",\"index\":1,"
                + "\"content_block\":{\"type\":\"tool_use\",\"name\":\"StructuredOutput\",\"input\":{}}}}");
        stream.accept(delta("{\"kind\":\"build\",\"options\":null,\"operations\":[{\"op\":\"fill\""));
        stream.accept(delta(",\"block\":\"stone\"},{\"o"));
        stream.accept(delta("p\":\"set\"}]}"));
        String result = "{\"type\":\"result\",\"is_error\":false,\"structured_output\":{\"kind\":\"build\"}}";
        stream.accept(result);

        assertEquals(List.of(ProgressListener.Phase.THINKING, ProgressListener.Phase.WRITING), recorder.phases);
        assertEquals(List.of(1, 2), recorder.ops);
        assertEquals(result, stream.resultLine());
    }

    @Test
    void noResultLineMeansNull() {
        ClaudeStream stream = new ClaudeStream(ProgressListener.NONE);
        stream.accept("{\"type\":\"system\"}");
        assertNull(stream.resultLine());
    }
}
