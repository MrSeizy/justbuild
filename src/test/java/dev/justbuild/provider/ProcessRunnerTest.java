package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessRunnerTest {
    @TempDir
    Path dir;

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void passesStdinAndCapturesOutput() throws Exception {
        ProcessResult r = ProcessRunner.run(List.of("/bin/sh", "-c", "cat; echo oops >&2; exit 3"), "hello",
                dir, Duration.ofSeconds(10), new CancellationToken());
        assertEquals(3, r.exitCode());
        assertEquals("hello", r.stdout());
        assertEquals("oops\n", r.stderr());
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void anExitedLauncherDoesNotLeaveItsKnownChildRunning() throws Exception {
        Path pidFile = dir.resolve("orphan.pid");
        long pid = -1;
        try {
            var result = ProcessRunner.run(List.of("/bin/sh", "-c",
                    "sleep 60 & echo $! > '" + pidFile + "'; sleep 0.3; echo done"), "", dir,
                    Duration.ofSeconds(5), new CancellationToken());
            pid = Long.parseLong(java.nio.file.Files.readString(pidFile).strip());
            assertEquals(0, result.exitCode());
            for (int i = 0; i < 40 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); i++) Thread.sleep(50);
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "the launcher's child survived");
        } finally {
            if (pid < 0 && java.nio.file.Files.exists(pidFile)) pid = Long.parseLong(java.nio.file.Files.readString(pidFile).strip());
            if (pid > 0) ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void streamsStdoutLinesAsTheyArrive() throws Exception {
        java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        ProcessResult r = ProcessRunner.run(List.of("/bin/sh", "-c", "printf 'a\\nb\\n'"), "", dir, Duration.ofSeconds(10),
                new CancellationToken(), lines::add);
        assertEquals(List.of("a", "b"), lines);
        assertEquals("a\nb\n", r.stdout());
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void timesOut() {
        var e = assertThrows(ProviderException.class, () -> ProcessRunner.run(List.of("/bin/sh", "-c", "exec sleep 5"), "",
                dir, Duration.ofMillis(300), new CancellationToken()));
        assertTrue(e.getMessage().startsWith("No response after"));
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void partialStdoutAndStderrExtendTheIdleDeadlineWithoutWaitingForNewlines() throws Exception {
        for (String redirect : List.of("", " >&2")) {
            var activity = new java.util.concurrent.atomic.AtomicInteger();
            var lines = new java.util.concurrent.CopyOnWriteArrayList<String>();
            String script = "for step in 1 2 3 4 5 6; do printf x" + redirect
                    + "; sleep 0.2; done; printf '\\n'" + redirect;
            var result = ProcessRunner.runWithIdleTimeout(List.of("/bin/sh", "-c", script), "", dir,
                    Duration.ofMillis(600), new CancellationToken(), lines::add, Map.of(), activity::incrementAndGet);
            assertEquals(0, result.exitCode());
            assertEquals("xxxxxx\n", redirect.isEmpty() ? result.stdout() : result.stderr());
            assertEquals(redirect.isEmpty() ? List.of("xxxxxx") : List.of(), lines);
            assertTrue(activity.get() >= 3, "partial output never reached the activity listener");
        }
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void anIdleTimeoutAfterInitialOutputAlsoKillsTheChild() throws Exception {
        Path pidFile = dir.resolve("idle-child.pid");
        var error = assertThrows(ProviderException.class, () -> ProcessRunner.runWithIdleTimeout(
                List.of("/bin/sh", "-c", "echo started; sleep 60 & echo $! > " + pidFile + "; wait"), "", dir,
                Duration.ofMillis(500), new CancellationToken(), line -> { }, Map.of(), () -> { }));
        assertTrue(error.getMessage().startsWith("No CLI output for"), error.getMessage());
        long pid = Long.parseLong(java.nio.file.Files.readString(pidFile).strip());
        for (int i = 0; i < 40 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); i++) Thread.sleep(50);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "the idle child kept running");
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void cancellationStillStopsAContinuouslyActiveCli() throws Exception {
        var token = new CancellationToken();
        var received = new java.util.concurrent.CountDownLatch(1);
        Thread cancel = Thread.ofVirtual().start(() -> {
            try { if (received.await(5, java.util.concurrent.TimeUnit.SECONDS)) token.cancel(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            assertThrows(CancelledException.class, () -> ProcessRunner.runWithIdleTimeout(
                    List.of("/bin/sh", "-c", "while :; do printf x; sleep 0.1; done"), "", dir,
                    Duration.ofSeconds(10), token, line -> { }, Map.of(), received::countDown));
        } finally {
            token.cancel();
            cancel.interrupt();
            cancel.join();
        }
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void diagnosticCommandsStillHaveATotalTimeLimitDespiteOutput() {
        var error = assertThrows(ProviderException.class, () -> ProcessRunner.run(List.of("/bin/sh", "-c",
                "while :; do echo status; sleep 0.1; done"), "", dir,
                Duration.ofMillis(400), new CancellationToken(), line -> { }));
        assertTrue(error.getMessage().startsWith("No response after"));
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aTimeoutAlsoStopsTheProcessesItStarted() throws Exception {
        // Like an npm launcher: the process we start runs the real CLI as its own child.
        Path pidFile = dir.resolve("child.pid");
        assertThrows(ProviderException.class, () -> ProcessRunner.run(List.of("/bin/sh", "-c",
                "sleep 60 & echo $! > " + pidFile + "; wait"), "", dir, Duration.ofMillis(500), new CancellationToken()));
        long pid = Long.parseLong(java.nio.file.Files.readString(pidFile).strip());
        for (int i = 0; i < 40 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); i++) Thread.sleep(50);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "the child process kept running");
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void stopsAProcessThatWritesTooMuch() {
        long started = System.nanoTime();
        var e = assertThrows(ProviderException.class, () -> ProcessRunner.run(List.of("/bin/sh", "-c", "exec yes"), "",
                dir, Duration.ofSeconds(20), new CancellationToken(), null, Map.of(), 64 * 1024, 4096));
        assertEquals("sh wrote more than 64 KB of output, so it was stopped.", e.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 10, "waited for the timeout instead");
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void stopsAStreamingProcessWhoseLineNeverEnds() {
        long started = System.nanoTime();
        var e = assertThrows(ProviderException.class, () -> ProcessRunner.run(List.of("/bin/sh", "-c",
                "yes x | tr -d '\\n'"), "", dir, Duration.ofSeconds(20), new CancellationToken(), line -> { },
                Map.of(), 64 * 1024, 4096));
        assertEquals("sh wrote more than 64 KB of output, so it was stopped.", e.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 10, "waited for the timeout instead");
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void keepsDrainingStderrButRetainsOnlyItsEnd() throws Exception {
        ProcessResult r = ProcessRunner.run(List.of("/bin/sh", "-c",
                "yes noise | head -c 199998 >&2; echo done >&2; echo ok"), "", dir, Duration.ofSeconds(20),
                new CancellationToken(), null, Map.of(), 64 * 1024, 4096);
        assertEquals("ok\n", r.stdout());
        assertTrue(r.stderr().length() <= 4096, "kept " + r.stderr().length() + " characters of stderr");
        assertTrue(r.stderr().endsWith("noise\ndone\n"), r.stderr());
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void cancels() {
        CancellationToken token = new CancellationToken();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            token.cancel();
        });
        assertThrows(CancelledException.class, () -> ProcessRunner.run(List.of("/bin/sh", "-c", "exec sleep 5"), "",
                dir, Duration.ofSeconds(10), token));
    }

    @Test
    void childEnvironmentDropsClaudeSessionVarsAndExtendsPath() {
        Map<String, String> env = ProcessRunner.childEnvironment(
                Map.of("CLAUDECODE", "1", "CLAUDE_CODE_ENTRYPOINT", "cli", "PATH", "/usr/bin:/custom", "HOME", "/h"), "/h");
        assertFalse(env.containsKey("CLAUDECODE"));
        assertFalse(env.containsKey("CLAUDE_CODE_ENTRYPOINT"));
        assertEquals("/usr/bin:/custom:/h/.local/bin:/opt/homebrew/bin:/usr/local/bin:/bin", env.get("PATH"));
        assertEquals("/h", env.get("HOME"));
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void nullOverrideRemovesAnInheritedVariableFromTheActualProcess() throws Exception {
        var overrides = new java.util.HashMap<String, String>();
        overrides.put("PATH", null);
        var result = ProcessRunner.run(List.of("/usr/bin/printenv", "PATH"), "", dir,
                Duration.ofSeconds(10), new CancellationToken(), null, overrides);
        assertEquals(1, result.exitCode());
        assertEquals("", result.stdout());
    }

    @Test
    void windowsPathKeepsItsKeyAndSeparator() {
        Map<String, String> env = ProcessRunner.childEnvironment(Map.of("Path", "C:\\Windows;C:\\tools"), "C:\\Users\\m", true);
        assertEquals("C:\\Windows;C:\\tools", env.get("Path"));
        assertFalse(env.containsKey("PATH"));
    }

    @Test
    void tailKeepsLastNonBlankLines() {
        assertEquals("b\nc", ProcessRunner.tail("a\n\nb\n  \nc\n", 2));
        assertEquals("", ProcessRunner.tail(null, 2));
    }
}
