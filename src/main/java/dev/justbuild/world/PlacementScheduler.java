package dev.justbuild.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Runs placement jobs in order with a shared per-tick block budget. Server thread only. */
public final class PlacementScheduler {
    private final Deque<PlacementJob> queue = new ArrayDeque<>();
    private final IntSupplier blocksPerTick;
    private final Consumer<PlacementJob> onFinished;

    public PlacementScheduler(IntSupplier blocksPerTick, Consumer<PlacementJob> onFinished) {
        this.blocksPerTick = blocksPerTick;
        this.onFinished = onFinished;
    }

    public void enqueue(PlacementJob job) {
        queue.addLast(job);
    }

    /** Shares the tick's budget among jobs in order; a job waiting for an unloaded chunk lets the next ones go on. */
    public void tick() {
        int budget = Math.max(1, blocksPerTick.getAsInt());
        for (PlacementJob job : List.copyOf(queue)) {
            if (budget <= 0) {
                break;
            }
            budget -= job.step(budget);
            if (job.done()) {
                queue.remove(job);
                onFinished.accept(job);
            } else if (!job.waiting()) {
                break;
            }
        }
    }

    /** Removes and returns this owner's queued jobs, unfinished. */
    public List<PlacementJob> cancel(String owner) {
        List<PlacementJob> removed = new ArrayList<>();
        for (Iterator<PlacementJob> it = queue.iterator(); it.hasNext(); ) {
            PlacementJob job = it.next();
            if (job.owner().equals(owner)) {
                it.remove();
                removed.add(job);
            }
        }
        return removed;
    }

    /** Cancels one revoked action without touching that player's unrelated work. */
    public boolean cancel(PlacementJob job) {
        return queue.remove(job);
    }

    /** Snapshot of the queued jobs, oldest first. */
    public List<PlacementJob> jobs() {
        return List.copyOf(queue);
    }

    public Optional<PlacementJob> active(String owner) {
        return queue.stream().filter(job -> job.owner().equals(owner)).findFirst();
    }

    public boolean busy(String owner) {
        return active(owner).isPresent();
    }

    /** Removes and returns every queued job (server stopping). */
    public List<PlacementJob> drain() {
        List<PlacementJob> all = new ArrayList<>(queue);
        queue.clear();
        return all;
    }
}
