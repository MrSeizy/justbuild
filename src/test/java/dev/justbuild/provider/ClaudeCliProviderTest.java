package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeCliProviderTest {
    @TempDir
    Path dir;

    static PlanRequest request(String model) {
        return new PlanRequest("SYSTEM", "USER", "{\"type\":\"object\"}", model, Duration.ofSeconds(10));
    }

    @Test
    void commandDisablesToolsAndKeepsSubscriptionAuth() {
        Path executable = dir.resolve("claude");
        List<String> cmd = ClaudeCliProvider.command(executable, request(""));
        assertEquals(List.of(executable.toString(), "-p", "--output-format", "stream-json", "--verbose",
                "--include-partial-messages", "--json-schema", "{\"type\":\"object\"}",
                "--system-prompt", "SYSTEM", "--tools", "", "--restricted", "--safe-mode", "--disable-slash-commands", "--strict-mcp-config",
                "--no-session-persistence"), cmd);
        assertFalse(cmd.contains("--bare"));
        List<String> withModel = ClaudeCliProvider.command(executable, request(" sonnet "));
        assertEquals(List.of("--model", "sonnet"), withModel.subList(withModel.size() - 2, withModel.size()));
    }

    @Test
    void extractsStructuredOutput() throws Exception {
        String stdout = """
                {"type":"result","subtype":"success","is_error":false,"result":"ignored",
                 "structured_output":{"kind":"build","title":"T","operations":[]}}""";
        String text = ClaudeOutput.extract(new ProcessResult(0, stdout, ""));
        assertEquals("{\"kind\":\"build\",\"title\":\"T\",\"operations\":[]}", text);
    }

    @Test
    void fallsBackToResultText() throws Exception {
        String stdout = "{\"is_error\":false,\"result\":\"```json\\n{\\\"kind\\\":\\\"question\\\"}\\n```\"}";
        assertTrue(ClaudeOutput.extract(new ProcessResult(0, stdout, "")).contains("\"kind\":\"question\""));
    }

    @Test
    void reportsApiErrors() {
        String stdout = """
                {"is_error":true,"terminal_reason":"api_error","result":"API Error: Couldn't connect"}""";
        var e = assertThrows(ProviderException.class, () -> ClaudeOutput.extract(new ProcessResult(1, stdout, "")));
        assertEquals("Claude: API Error: Couldn't connect", e.getMessage());
    }

    @Test
    void reportsNonJsonFailures() {
        var e = assertThrows(ProviderException.class,
                () -> ClaudeOutput.extract(new ProcessResult(2, "", "fatal: not logged in\n")));
        assertEquals("Claude CLI exited with code 2: fatal: not logged in", e.getMessage());
    }

    @Test
    void tellsThePlayerToUpdateAnOldClaudeCode() {
        var e = assertThrows(ProviderException.class,
                () -> ClaudeOutput.extract(new ProcessResult(1, "", "error: unknown option '--restricted'\n")));
        assertTrue(e.getMessage().contains("too old"), e.getMessage());
        assertTrue(e.getMessage().contains("claude update"), e.getMessage());
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void runsTheConfiguredBinary() throws Exception {
        Path fake = dir.resolve("claude");
        Files.writeString(fake, """
                #!/bin/sh
                [ "$CLAUDE_CODE_DISABLE_CLAUDE_MDS" = 1 ] || exit 11
                [ "$CLAUDE_CODE_DISABLE_AUTO_MEMORY" = 1 ] || exit 12
                [ "$CLAUDE_CODE_DISABLE_ATTACHMENTS" = 1 ] || exit 13
                cat > /dev/null
                echo '{"type":"system","subtype":"init"}'
                for step in 1 2 3 4 5 6; do
                  echo '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"thinking"}}}'
                  sleep 0.2
                done
                echo '{"type":"result","is_error":false,"structured_output":{"kind":"build"}}'
                """);
        assertTrue(fake.toFile().setExecutable(true));
        var provider = new ClaudeCliProvider(fake.toString(), List.of());
        var progress = new RecordingListener();
        var shortIdle = new PlanRequest("SYSTEM", "USER", "{}", "", Duration.ofMillis(600));
        assertEquals("{\"kind\":\"build\"}", provider.complete(shortIdle, new CancellationToken(), progress));
        assertTrue(progress.activities.get() >= 3, "thinking deltas did not forward activity");
        assertTrue(progress.phases.isEmpty(), "this fixture should not need a visible phase change to stay alive");
    }
}
