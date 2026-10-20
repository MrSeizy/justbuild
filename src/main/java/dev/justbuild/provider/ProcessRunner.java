package dev.justbuild.provider;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Runs a CLI with stdin, a total or inactivity timeout, cancellation, and a GUI-friendly environment. */
public final class ProcessRunner {
    private static final List<String> STRIPPED_ENV = List.of("CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT");

    private ProcessRunner() {
    }

    public static ProcessResult run(List<String> command, String stdin, Path workDir, Duration timeout,
            CancellationToken token) throws ProviderException {
        return run(command, stdin, workDir, timeout, token, null);
    }

    /** Like {@link #run} but also hands each stdout line to {@code stdoutLines} as it arrives (on a reader thread). */
    public static ProcessResult run(List<String> command, String stdin, Path workDir, Duration timeout,
            CancellationToken token, Consumer<String> stdoutLines) throws ProviderException {
        return run(command, stdin, workDir, timeout, token, stdoutLines, Map.of());
    }

    /** Like the above, with overrides on the child environment. A null value removes an inherited variable. */
    public static ProcessResult run(List<String> command, String stdin, Path workDir, Duration timeout,
            CancellationToken token, Consumer<String> stdoutLines, Map<String, String> envOverrides)
            throws ProviderException {
        return run(command, stdin, workDir, timeout, token, stdoutLines, envOverrides, OutputLimit.MAX_BYTES,
                OutputLimit.STDERR_TAIL_BYTES);
    }

    /** Planning calls keep running while stdout or stderr arrives, even without a complete JSON line. */
    public static ProcessResult runWithIdleTimeout(List<String> command, String stdin, Path workDir, Duration timeout,
            CancellationToken token, Consumer<String> stdoutLines, Map<String, String> envOverrides,
            Runnable activity) throws ProviderException {
        return run(command, stdin, workDir, timeout, token, stdoutLines, envOverrides, OutputLimit.MAX_BYTES,
                OutputLimit.STDERR_TAIL_BYTES, activity, true);
    }

    /** Stops the process once stdout passes {@code maxStdoutBytes}; keeps only the last {@code stderrTailBytes}. */
    static ProcessResult run(List<String> command, String stdin, Path workDir, Duration timeout,
            CancellationToken token, Consumer<String> stdoutLines, Map<String, String> envOverrides,
            long maxStdoutBytes, int stderrTailBytes) throws ProviderException {
        return run(command, stdin, workDir, timeout, token, stdoutLines, envOverrides, maxStdoutBytes, stderrTailBytes,
                null, false);
    }

    private static ProcessResult run(List<String> command, String stdin, Path workDir, Duration timeout,
            CancellationToken token, Consumer<String> stdoutLines, Map<String, String> envOverrides,
            long maxStdoutBytes, int stderrTailBytes, Runnable activity, boolean idle) throws ProviderException {
        token.throwIfCancelled();
        String name = Path.of(command.get(0)).getFileName().toString();
        String lowerName = name.toLowerCase(java.util.Locale.ROOT);
        if (lowerName.endsWith(".cmd") || lowerName.endsWith(".bat")) {
            throw new ProviderException("Windows batch launchers are not supported. Configure the provider's native executable instead of " + name + ".");
        }
        ProcessBuilder builder = new ProcessBuilder(command).directory(workDir.toFile());
        Map<String, String> env = builder.environment();
        Map<String, String> child = childEnvironment(env, System.getProperty("user.home"));
        envOverrides.forEach((key, value) -> { if (value == null) child.remove(key); else child.put(key, value); });
        env.clear();
        env.putAll(child);
        IdleTimeout deadline = idle ? new IdleTimeout(timeout) : null;
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ProviderException("Could not start " + name + ": " + e.getMessage());
        }
        // Once a launcher exits, its children disappear from descendants(). Remember them while it is alive.
        Set<ProcessHandle> descendants = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Thread tracker = Thread.ofVirtual().name("justbuild-cli-children").start(() -> {
            try {
                while (process.isAlive()) {
                    process.descendants().forEach(descendants::add);
                    Thread.sleep(25);
                }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        token.onCancel(() -> kill(process, descendants));
        if (idle) deadline.activity();
        InputStream stdout = OutputLimit.limit(process.getInputStream(), maxStdoutBytes);
        InputStream stderr = process.getErrorStream();
        if (idle) {
            Runnable received = () -> {
                deadline.activity();
                try {
                    if (activity != null) activity.run();
                } catch (RuntimeException ignored) {
                    // Progress forwarding is best effort; it must not stop draining the CLI's output.
                }
            };
            stdout = observe(stdout, received);
            stderr = observe(stderr, received);
        }
        CompletableFuture<String> out = stdoutLines == null ? drain(stdout, process)
                : drainLines(stdout, stdoutLines, process);
        CompletableFuture<String> err = drainTail(stderr, stderrTailBytes);
        // Written on its own thread so a CLI that never reads stdin cannot block us past the timeout.
        Thread.ofVirtual().name("justbuild-stdin").start(() -> {
            try (OutputStream in = process.getOutputStream()) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // The process exited before reading stdin; its exit code and stderr explain why.
            }
        });
        try {
            boolean finished;
            if (idle) {
                do {
                    finished = process.waitFor(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
                } while (!finished && deadline.remainingNanos() > 0);
            } else {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
            if (!finished) {
                kill(process);
                token.throwIfCancelled();
                throw new ProviderException((idle ? "No CLI output for " : "No response after ")
                        + Math.max(1, timeout.toSeconds()) + " s");
            }
            token.throwIfCancelled();
            descendants.forEach(ProcessHandle::destroyForcibly);
            return new ProcessResult(process.exitValue(), out.get(10, TimeUnit.SECONDS), err.get(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof OutputLimit.ExceededException tooMuch) {
                throw new ProviderException(name + " wrote " + tooMuch.getMessage() + " of output, so it was stopped.");
            }
            throw new ProviderException("Could not read output from " + name + ": " + e.getMessage());
        } catch (TimeoutException e) {
            throw new ProviderException("Could not read output from " + name + ": " + e.getMessage());
        } finally {
            tracker.interrupt();
            kill(process, descendants);
        }
    }

    /** Observe bytes before line buffering, so partial lines and otherwise unrecognized events count as activity. */
    private static InputStream observe(InputStream input, Runnable activity) {
        return new FilterInputStream(input) {
            @Override public int read() throws IOException {
                int value = in.read();
                if (value >= 0) activity.run();
                return value;
            }

            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = in.read(bytes, offset, length);
                if (count > 0) activity.run();
                return count;
            }
        };
    }

    /**
     * Stops the process and everything it started. A CLI installed with npm is a launcher that runs the real program
     * as its child and can't pass on a forced kill, so the child would keep running (and spending the player's quota).
     */
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void kill(Process process, Set<ProcessHandle> descendants) {
        process.descendants().forEach(descendants::add);
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** The parent environment minus Claude Code session markers, with common CLI install dirs on PATH. */
    static Map<String, String> childEnvironment(Map<String, String> parent, String home) {
        return childEnvironment(parent, home, File.pathSeparatorChar == ';');
    }

    static Map<String, String> childEnvironment(Map<String, String> parent, String home, boolean windows) {
        Map<String, String> env = new HashMap<>(parent);
        STRIPPED_ENV.forEach(env::remove);
        // Windows names it "Path"; keep whatever key the parent used.
        String key = env.keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
        String separator = windows ? ";" : ":";
        Set<String> path = new LinkedHashSet<>();
        String current = env.get(key);
        if (current != null) {
            for (String entry : current.split(Pattern.quote(separator))) {
                if (!entry.isBlank()) {
                    path.add(entry);
                }
            }
        }
        if (!windows) {
            path.addAll(List.of(home + "/.local/bin", "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin"));
        }
        env.put(key, String.join(separator, path));
        return env;
    }

    /** Last {@code lines} non-blank lines, for error messages. */
    public static String tail(String text, int lines) {
        if (text == null) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                kept.add(line.strip());
            }
        }
        String joined = String.join("\n", kept.subList(Math.max(0, kept.size() - lines), kept.size()));
        return joined.length() <= 400 ? joined : joined.substring(joined.length() - 400);
    }

    /** {@code stream} is limited; past the limit the process is stopped here, since run() is waiting for it to exit. */
    private static CompletableFuture<String> drainLines(InputStream stream, Consumer<String> listener, Process process) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Thread.ofVirtual().name("justbuild-drain").start(() -> {
            StringBuilder all = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    all.append(line).append('\n');
                    try {
                        listener.accept(line);
                    } catch (RuntimeException ignored) {
                        // progress is best effort; never lose output because a listener failed
                    }
                }
                result.complete(all.toString());
            } catch (IOException e) {
                if (e instanceof OutputLimit.ExceededException) kill(process);
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    /** {@code stream} is limited; past the limit the process is stopped here, since run() is waiting for it to exit. */
    private static CompletableFuture<String> drain(InputStream stream, Process process) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Thread.ofVirtual().name("justbuild-drain").start(() -> {
            try (stream) {
                result.complete(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                if (e instanceof OutputLimit.ExceededException) kill(process);
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    /**
     * Reads to the end, so a process writing a lot of stderr never blocks on a full pipe, but keeps only the last
     * {@code tailBytes}: errors are reported from their last lines.
     */
    private static CompletableFuture<String> drainTail(InputStream stream, int tailBytes) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Thread.ofVirtual().name("justbuild-drain").start(() -> {
            ByteArrayOutputStream kept = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            try (stream) {
                for (int n = stream.read(buffer); n != -1; n = stream.read(buffer)) {
                    kept.write(buffer, 0, n);
                    if (kept.size() > 2 * tailBytes) {
                        byte[] all = kept.toByteArray();
                        kept.reset();
                        kept.write(all, all.length - tailBytes, tailBytes);
                    }
                }
                byte[] all = kept.toByteArray();
                int from = Math.max(0, all.length - tailBytes);
                result.complete(new String(all, from, all.length - from, StandardCharsets.UTF_8));
            } catch (IOException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }
}
