package dev.justbuild.session;

import static org.junit.jupiter.api.Assertions.*;
import dev.justbuild.plan.BuildPlan;
import dev.justbuild.world.BuildRecord;
import dev.justbuild.world.DesignThumbnail;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VersionThumbnailsTest {
    @Test void lookupNeverGeneratesOnTheSyncThreadAndCloseDiscardsQueuedCompletions() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>(); var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger(); var notifications = new AtomicInteger();
        var result = new DesignThumbnail.Image(List.of(0x123456), "0".repeat(256));
        var version = new BuildRecord.Version("fixture", new BuildPlan("fixture", "", List.of()), 0);
        var caller = Thread.currentThread();
        try (var cache = new VersionThumbnails(callbacks::add, id -> notifications.incrementAndGet(), plan -> {
            assertNotSame(caller, Thread.currentThread()); calls.incrementAndGet(); started.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); }
            return result;
        })) {
            assertEquals(DesignThumbnail.Image.EMPTY, cache.get(version)); assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(DesignThumbnail.Image.EMPTY, cache.get(version)); assertEquals(1, calls.get());
            release.countDown(); var callback = callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(callback); callback.run();
            assertSame(result, cache.get(version)); assertEquals(1, notifications.get());
            var later = new BuildRecord.Version("later", version.plan(), 0); cache.get(later);
            var stale = callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(stale); cache.close(); stale.run();
            assertEquals(1, notifications.get()); assertEquals(DesignThumbnail.Image.EMPTY, cache.get(later));
        } finally { release.countDown(); }
    }
    @Test void failedGenerationFinishesAndDoesNotPreventLaterThumbnails() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>(); var calls = new AtomicInteger();
        var plan = new BuildPlan("fixture", "", List.of());
        var first = new BuildRecord.Version("failed", plan, 0); var second = new BuildRecord.Version("good", plan, 0);
        var image = new DesignThumbnail.Image(List.of(0x123456), "0".repeat(256));
        try (var cache = new VersionThumbnails(callbacks::add, ignored -> {}, p -> {
            if (calls.incrementAndGet() == 1) throw new IllegalArgumentException("bad thumbnail");
            return image;
        })) {
            cache.get(first); var failed = callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(failed); failed.run();
            assertSame(DesignThumbnail.Image.EMPTY, cache.get(first));
            cache.get(second); var success = callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(success); success.run();
            assertSame(image, cache.get(second)); assertEquals(2, calls.get());
        }
    }

}
