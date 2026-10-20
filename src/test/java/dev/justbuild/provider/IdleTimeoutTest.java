package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class IdleTimeoutTest {
    @Test
    void anAlreadyWaitingRelayCanOutliveItsOriginalDeadline() throws Exception {
        var idle = new IdleTimeout(Duration.ofMillis(600));
        var result = new CompletableFuture<String>();
        Thread producer = Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < 6; i++) {
                    idle.activity();
                    Thread.sleep(200);
                }
                result.complete("finished");
            } catch (InterruptedException e) {
                result.completeExceptionally(e);
            }
        });
        try {
            assertEquals("finished", idle.await(result));
        } finally {
            producer.interrupt();
            producer.join();
        }
    }

    @Test
    void silenceExpiresAndCancellationDoesNotWaitForTheDeadline() {
        var idle = new IdleTimeout(Duration.ofMillis(150));
        assertThrows(TimeoutException.class, () -> idle.await(new CompletableFuture<>()));
        var cancelled = new CompletableFuture<String>();
        cancelled.cancel(false);
        assertThrows(CancellationException.class,
                () -> new IdleTimeout(Duration.ofHours(1)).await(cancelled));
    }
}
