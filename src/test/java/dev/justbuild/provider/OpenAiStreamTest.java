package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class OpenAiStreamTest {
    static String chunk(String content) {
        com.google.gson.JsonObject delta = new com.google.gson.JsonObject();
        delta.addProperty("content", content);
        com.google.gson.JsonObject choice = new com.google.gson.JsonObject();
        choice.add("delta", delta);
        com.google.gson.JsonArray choices = new com.google.gson.JsonArray();
        choices.add(choice);
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.add("choices", choices);
        return "data: " + root;
    }

    @Test
    void assemblesContentAndCountsOperations() throws Exception {
        List<Integer> ops = new ArrayList<>();
        List<ProgressListener.Phase> phases = new ArrayList<>();
        ProgressListener listener = new ProgressListener() {
            @Override
            public void phase(Phase phase) {
                phases.add(phase);
            }

            @Override
            public void operations(int count) {
                ops.add(count);
            }
        };
        String content = OpenAiStream.read(Stream.of(": keep-alive", "", chunk("{\"operations\":[{\"o"), "",
                chunk("p\":\"set\"},{\"op\":"), chunk("\"fill\"}]}"), "data: [DONE]", chunk("ignored")), listener);
        assertEquals("{\"operations\":[{\"op\":\"set\"},{\"op\":\"fill\"}]}", content);
        // the second chunk completes the split key and a whole second one, so the count jumps straight to 2
        assertEquals(List.of(2), ops);
        assertEquals(List.of(ProgressListener.Phase.WRITING), phases);
    }

    @Test
    void reportsReasoningAsThinkingOnce() throws Exception {
        RecordingListener progress = new RecordingListener();
        String content = OpenAiStream.read(Stream.of(
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"Let me plan\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\" a tavern.\"}}]}",
                // OpenRouter's spelling
                "data: {\"choices\":[{\"delta\":{\"reasoning\":\" Stone base.\"}}]}",
                chunk("{\"kind\":\"build\"}"), "data: [DONE]"), progress);
        assertEquals("{\"kind\":\"build\"}", content);
        assertEquals(List.of(ProgressListener.Phase.THINKING, ProgressListener.Phase.WRITING), progress.phases);
    }

    @Test
    void emptyContentDoesNotHideReasoningOrCountOperationsTwice() throws Exception {
        RecordingListener progress = new RecordingListener();
        String content = OpenAiStream.read(Stream.of(chunk(""),
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"Planning\",\"content\":\"\"}}]}",
                chunk("{\"op\""), chunk(""), chunk(":\"set\"}"), "data: [DONE]"), progress);
        assertEquals("{\"op\":\"set\"}", content);
        assertEquals(List.of(ProgressListener.Phase.THINKING, ProgressListener.Phase.WRITING), progress.phases);
        assertEquals(List.of(1), progress.ops);
    }

    @Test
    void reportsTheUsageChunk() throws Exception {
        RecordingListener progress = new RecordingListener();
        String usage = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":340}}";
        OpenAiStream.read(Stream.of(chunk("{\"kind\":\"build\"}"), usage, "data: [DONE]"), progress);
        assertArrayEquals(new long[] {1200, 340}, progress.tokens.get(0));
        RecordingListener none = new RecordingListener();
        OpenAiStream.read(Stream.of(chunk("{}"), "data: [DONE]"), none);
        assertArrayEquals(new long[] {0, 0}, none.tokens.get(0), "a call without usage still counts as a call");
    }

    @Test
    void reportsRefusalsErrorsAndEmptyStreams() {
        var refusal = assertThrows(ProviderException.class, () -> OpenAiStream.read(Stream.of(
                "data: {\"choices\":[{\"delta\":{\"refusal\":\"No.\"}}]}", "data: [DONE]"), ProgressListener.NONE));
        assertEquals("The model refused: No.", refusal.getMessage());
        var error = assertThrows(ProviderException.class, () -> OpenAiStream.read(Stream.of(
                "data: {\"error\":{\"message\":\"overloaded\"}}"), ProgressListener.NONE));
        assertEquals("Endpoint error: overloaded", error.getMessage());
        var empty = assertThrows(ProviderException.class, () -> OpenAiStream.read(Stream.of("data: [DONE]"),
                ProgressListener.NONE));
        assertEquals("Endpoint returned an empty message", empty.getMessage());
    }
}
