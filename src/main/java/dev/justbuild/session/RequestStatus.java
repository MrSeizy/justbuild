package dev.justbuild.session;

import dev.justbuild.provider.ProgressListener;
import java.time.Duration;
import java.time.Instant;

/** Progress of one AI call: written by the provider's worker thread, read by the server tick. */
final class RequestStatus implements ProgressListener {
    static final int HEARTBEAT_SECONDS = 60;

    final String provider;
    final Instant started;
    private final dev.justbuild.provider.UsageTracker usage;
    private volatile Instant finished;
    private volatile Phase phase = Phase.WAITING;
    private volatile int operations;
    private volatile int reviewRound;
    private volatile int reviewRounds;
    private volatile boolean shrinking;
    private volatile ReviewRounds.Stage stage;
    /** How many review rounds and smaller-plan tries have started, for the "Plan ready" line. */
    private volatile int reviewsStarted;
    private volatile int smallerTries;
    private volatile String note;
    private final java.util.concurrent.atomic.AtomicLong tokensIn = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong tokensOut = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    /** Notices waiting for the server tick to post: {key, message}. */
    private final java.util.Queue<String[]> notices = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** Server thread only: elapsed seconds at which the next chat heartbeat is due. */
    private long nextHeartbeat = HEARTBEAT_SECONDS;

    RequestStatus(String provider, Instant started) { this(provider, started, new dev.justbuild.provider.UsageTracker()); }
    RequestStatus(String provider, Instant started, dev.justbuild.provider.UsageTracker usage) {
        this.provider = provider; this.started = started; this.usage = usage;
    }
    dev.justbuild.provider.UsageTracker.Call begin(boolean review) throws dev.justbuild.provider.ProviderException {
        var call = usage.begin(review, this); calls.incrementAndGet(); return call;
    }

    @Override
    public void phase(Phase value) {
        phase = value;
    }

    @Override
    public void operations(int count) {
        operations = count;
    }

    @Override
    public void note(String headline) {
        note = headline;
    }

    @Override
    public void tokens(long input, long output) {
        tokensIn.addAndGet(Math.max(0, input));
        tokensOut.addAndGet(Math.max(0, output));
    }

    @Override
    public void notice(String key, String message) {
        notices.add(new String[] {key, message});
    }

    String note() {
        return note;
    }

    long tokensIn() {
        return tokensIn.get();
    }

    long tokensOut() {
        return tokensOut.get();
    }

    int calls() {
        return calls.get();
    }

    /** Server thread: the next notice to post, or null. */
    String[] nextNotice() {
        return notices.poll();
    }

    /** Starts the separate reviewer's critique for round {@code round} of {@code rounds}. */
    void critiquing(int round, int rounds) {
        start(round, rounds, false);
        stage = ReviewRounds.Stage.CRITIQUE;
        reviewsStarted = Math.max(reviewsStarted, round);
    }

    /** Starts the builder's repair of a reviewed plan in round {@code round} of {@code rounds}. */
    void repairing(int round, int rounds) {
        start(round, rounds, false);
        stage = ReviewRounds.Stage.REPAIR;
        reviewsStarted = Math.max(reviewsStarted, round);
    }

    /** Starts the builder's own review (self-review or the quick correction); the operation count restarts. */
    void reviewing(int round, int rounds) {
        start(round, rounds, false);
        stage = ReviewRounds.Stage.REVIEW;
        reviewsStarted = Math.max(reviewsStarted, round);
    }

    /** Starts retry {@code round} of {@code rounds} asking for a plan under the block limit. */
    void shrinking(int round, int rounds) {
        start(round, rounds, true);
        smallerTries = round;
    }

    private void start(int round, int rounds, boolean shrink) {
        shrinking = shrink;
        stage = null;
        reviewRounds = rounds;
        reviewRound = round;
        operations = 0;
        note = null;
        phase = Phase.WAITING;
    }

    ReviewRounds.Stage stage() {
        return stage;
    }

    int reviewsStarted() {
        return reviewsStarted;
    }

    int smallerTries() {
        return smallerTries;
    }

    boolean isShrinking() {
        return shrinking;
    }

    int reviewRound() {
        return reviewRound;
    }

    int reviewRounds() {
        return reviewRounds;
    }

    Phase currentPhase() {
        return phase;
    }

    int operationCount() {
        return operations;
    }

    Duration elapsed(Instant now) {
        Instant end = finished;
        return Duration.between(started, end == null ? now : end);
    }

    /** Schedule from the message actually sent, so resuming a paused world never dumps missed chat updates. */
    boolean heartbeatDue(Duration elapsed) {
        long seconds = elapsed.toSeconds();
        if (seconds < nextHeartbeat) return false;
        nextHeartbeat = seconds + HEARTBEAT_SECONDS;
        return true;
    }

    void finished() { if (finished == null) finished = Instant.now(); }
}
