package dev.justbuild.provider;

import java.util.ArrayList;
import java.util.List;

/** Cancels one in-flight request and runs registered cleanups (kill a process, abort HTTP). */
public final class CancellationToken {
    private final List<Runnable> listeners = new ArrayList<>();
    private boolean cancelled;

    public void cancel() {
        List<Runnable> toRun;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            toRun = List.copyOf(listeners);
            listeners.clear();
        }
        toRun.forEach(Runnable::run);
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    public void onCancel(Runnable listener) {
        synchronized (this) {
            if (!cancelled) {
                listeners.add(listener);
                return;
            }
        }
        listener.run();
    }

    public void throwIfCancelled() throws CancelledException {
        if (isCancelled()) {
            throw new CancelledException();
        }
    }
}
