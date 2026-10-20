package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class GrokCliProviderTest {
    @TempDir Path dir;
    static PlanRequest request() { return new PlanRequest("Return JSON only.", "USER REQUEST", "{\"type\":\"object\"}", "grok-test", Duration.ofSeconds(10)); }

    @Test void requiresCompleteStructuredOutputAndSanitizesFailures() throws Exception {
        assertEquals("{\"ok\":true}", GrokCliProvider.extract(new ProcessResult(0,
                "{\"stopReason\":\"end_turn\",\"structuredOutput\":{\"ok\":true}}", ""), ProgressListener.NONE));
        for (String bad : List.of("not json secret", "{\"stopReason\":\"max_turns\",\"structuredOutput\":{\"ok\":true}}",
                "{\"type\":\"error\",\"message\":\"secret\"}", "{\"stopReason\":\"end_turn\",\"text\":\"secret\"}"))
            assertFalse(assertThrows(ProviderException.class, () -> GrokCliProvider.extract(new ProcessResult(0, bad, ""),
                    ProgressListener.NONE)).getMessage().contains("secret"));
        assertTrue(assertThrows(ProviderException.class, () -> GrokCliProvider.extract(new ProcessResult(1, "", "not authenticated secret"),
                ProgressListener.NONE)).getMessage().contains("grok login"));
    }

    @Test void distinguishesUnauthenticatedFallbackCatalog() throws Exception {
        assertEquals(List.of("grok-4.7", "grok-4.6"), GrokCliProvider.parseModels("You are logged in with grok.com.\n\nAvailable models:\n * grok-4.7 (default)\n - grok-4.6\n - bad model\n"));
        assertThrows(ProviderException.class, () -> GrokCliProvider.parseModels("You are not authenticated.\n - grok-4.5"));
        assertThrows(ProviderException.class, () -> GrokCliProvider.parseModels("You are logged in with grok.com.\n"));
    }

    @Test void authReferenceSurvivesReplacementWithoutCopyingPersonalContext() throws Exception {
        Path original = dir.resolve("real"); Files.createDirectory(original);
        Path auth = original.resolve("auth.json"); Files.writeString(auth, "FIRST");
        Files.writeString(original.resolve("AGENTS.md"), "PRIVATE INSTRUCTIONS");
        Path work = Files.createDirectory(dir.resolve("work"));
        var env = GrokCliProvider.isolatedContext(work, auth);
        assertEquals(auth.toString(), env.get("GROK_AUTH_PATH"));
        assertNotEquals(original.toString(), env.get("GROK_HOME"));
        assertFalse(Files.exists(Path.of(env.get("GROK_HOME"), "auth.json")));
        assertFalse(Files.exists(Path.of(env.get("HOME"), "AGENTS.md")));
        Path replacement = original.resolve("replacement.json");
        Files.writeString(replacement, "REPLACED");
        Files.move(replacement, auth, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals("REPLACED", Files.readString(Path.of(env.get("GROK_AUTH_PATH"))));
        assertEquals("0", env.get("GROK_LOGIN_ENV"));
        assertEquals("0", env.get("GROK_CLAUDE_HOOKS_ENABLED"));
        assertEquals("0", env.get("GROK_MANAGED_MCPS_ENABLED"));
    }

    @Test @DisabledOnOs(OS.WINDOWS) void runsConfiguredBinaryWithPromptFileAndCleansTemporaryContext() throws Exception {
        Path marker = dir.resolve("work-path"), fake = dir.resolve("grok");
        Files.writeString(fake, """
                #!/bin/sh
                [ "$GROK_MEMORY" = 0 ] || exit 11
                [ "$GROK_WORKFLOWS" = 0 ] || exit 12
                [ "$GROK_LOGIN_ENV" = 0 ] || exit 13
                [ "$2" != "" ] || exit 14
                [ -f "$2" ] || exit 15
                grep -q 'USER REQUEST' "$2" || exit 16
                case "$*" in *--disallowed-tools*--deny*) ;; *) exit 17 ;; esac
                printf '%%s' "$PWD" > '%s'
                for step in 1 2 3 4 5 6; do printf . >&2; sleep 0.2; done
                echo '{"stopReason":"end_turn","structuredOutput":{"kind":"build","operations":[]}}'
                """.formatted(marker));
        assertTrue(fake.toFile().setExecutable(true));
        var provider = new GrokCliProvider(fake.toString(), List.of(), dir.resolve("auth.json"));
        var shortIdle = new PlanRequest("SYSTEM", "USER REQUEST", "{}", "grok-test", Duration.ofMillis(600));
        var progress = new RecordingListener();
        assertTrue(provider.complete(shortIdle, new CancellationToken(), progress).contains("operations"));
        assertTrue(progress.activities.get() >= 3, "stderr activity was not forwarded");
        assertFalse(Files.exists(Path.of(Files.readString(marker))));
    }
}
