package dev.justbuild.provider;

import java.util.ArrayList;
import java.util.List;

/** Records everything a backend reports, for tests. */
final class RecordingListener implements ProgressListener {
    final java.util.concurrent.atomic.AtomicInteger activities = new java.util.concurrent.atomic.AtomicInteger();
    final List<Phase> phases = new ArrayList<>();
    final List<Integer> ops = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    final List<long[]> tokens = new ArrayList<>();
    final List<String[]> notices = new ArrayList<>();

    @Override
    public void activity() {
        activities.incrementAndGet();
    }

    @Override
    public void phase(Phase phase) {
        phases.add(phase);
    }

    @Override
    public void operations(int count) {
        ops.add(count);
    }

    @Override
    public void note(String headline) {
        notes.add(headline);
    }

    @Override
    public void tokens(long input, long output) {
        tokens.add(new long[] {input, output});
    }

    @Override
    public void notice(String key, String message) {
        notices.add(new String[] {key, message});
    }
}
