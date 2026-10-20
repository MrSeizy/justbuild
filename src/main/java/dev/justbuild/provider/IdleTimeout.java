package dev.justbuild.provider;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A monotonic inactivity deadline. Activity extends the wait without imposing a total runtime limit. */
public final class IdleTimeout {
    private final long timeoutNanos;
    private long lastActivity = System.nanoTime();

    public IdleTimeout(Duration timeout) {
        timeoutNanos = timeout.toNanos();
        if (timeoutNanos <= 0) throw new IllegalArgumentException("The inactivity timeout must be positive.");
    }

    public synchronized void activity() {
        lastActivity = System.nanoTime();
    }

    public synchronized long remainingNanos() {
        return Math.max(0, timeoutNanos - (System.nanoTime() - lastActivity));
    }

    /** A renewed deadline must also extend a wait that was already in progress. */
    public <T> T await(CompletableFuture<T> result) throws InterruptedException, ExecutionException, TimeoutException {
        while (true) {
            try {
                return result.get(remainingNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                if (remainingNanos() == 0) throw e;
            }
        }
    }
}
