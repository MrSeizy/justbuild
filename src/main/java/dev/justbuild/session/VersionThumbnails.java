package dev.justbuild.session;

import dev.justbuild.world.BuildRecord;
import dev.justbuild.world.DesignThumbnail;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Server-thread cache with bounded background generation. A state sync only looks up or queues work. */
final class VersionThumbnails implements AutoCloseable {
    private static final int MAX_CACHE = 256, MAX_PENDING = 32;
    private final LinkedHashMap<String, DesignThumbnail.Image> cache = new LinkedHashMap<>(MAX_CACHE, .75f, true);
    private final Set<String> pending = new HashSet<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING), Thread.ofPlatform().daemon().name("JustBuild thumbnails").factory());
    private final Consumer<Runnable> onServer;
    private final Consumer<String> ready;
    private final java.util.function.Function<dev.justbuild.plan.BuildPlan, DesignThumbnail.Image> generate;
    private boolean closed;
    VersionThumbnails(Consumer<Runnable> onServer, Consumer<String> ready) { this(onServer, ready, DesignThumbnail::create); }
    VersionThumbnails(Consumer<Runnable> onServer, Consumer<String> ready,
            java.util.function.Function<dev.justbuild.plan.BuildPlan, DesignThumbnail.Image> generate) {
        this.onServer = onServer; this.ready = ready; this.generate = generate;
    }
    DesignThumbnail.Image get(BuildRecord.Version version) {
        var image = cache.get(version.id());
        if (image != null) return image;
        if (!closed && pending.size() < MAX_PENDING && pending.add(version.id())) {
            // BuildPlan owns immutable operation lists. Generation uses registry state and an empty world only.
            var plan = version.plan(); var id = version.id();
            worker.execute(() -> {
                DesignThumbnail.Image generated;
                try { generated = java.util.Objects.requireNonNull(generate.apply(plan)); }
                catch (RuntimeException e) { generated = DesignThumbnail.Image.EMPTY; }
                var made = generated;
                onServer.accept(() -> {
                    if (closed) return;
                    pending.remove(id); cache.put(id, made);
                    if (cache.size() > MAX_CACHE) cache.pollFirstEntry();
                    ready.accept(id);
                });
            });
        }
        return DesignThumbnail.Image.EMPTY;
    }
    @Override public void close() { closed = true; pending.clear(); cache.clear(); worker.shutdownNow(); }
}
