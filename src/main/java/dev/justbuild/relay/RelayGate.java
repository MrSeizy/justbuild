package dev.justbuild.relay;

import dev.justbuild.prompt.AlternativeRequests;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * The player's game answers a server's AI request only right after the player started something, so a server cannot
 * spend the player's subscription on its own. An AI-starting /justbuild command allows {@link #CALLS} calls for
 * {@link #WINDOW} of inactivity. Output from the currently authorized call extends that window without adding calls.
 * Chat rearms it only when the player's own AI has just asked a question (the chat is the answer),
 * and never more than {@link #HOURLY} calls are allowed in any hour.
 */
public final class RelayGate {
    public static final Duration WINDOW = Duration.ofMinutes(15);
    /** A plan, guides, four shared spatial/catalog reads, two size retries, two critiques and two repairs, and two spare. */
    public static final int CALLS = 14;
    /** A hard ceiling per rolling hour, however often the player starts builds. */
    public static final int HOURLY = 30;

    private Instant until = Instant.MIN;
    private int remaining;
    private boolean awaitingAnswer;
    private boolean fresh;
    private boolean lastWasFirst;
    private int requestedOptions = 1;
    private final Deque<Instant> recent = new ArrayDeque<>();

    /** An outgoing command, without the slash. */
    public synchronized void command(String command, Instant now) {
        if (startsAi(command)) {
            awaitingAnswer = false;
            String[] words = command.strip().split("\\s+", 3);
            if (!words[1].equalsIgnoreCase("answer")) requestedOptions = 1;
            if (words[1].equalsIgnoreCase("alternatives") && words.length == 3) {
                try { requestedOptions = AlternativeRequests.parse(words[2]).count(); }
                catch (IllegalArgumentException ignored) { }
            }
            arm(now);
        }
    }

    /** Outgoing chat: renews the window only as the answer to a question the player's AI just asked. */
    public synchronized void chat(Instant now) {
        if (awaitingAnswer && now.isBefore(until)) {
            awaitingAnswer = false;
            arm(now);
        }
    }

    /** The player's AI answered with a question; the next chat line is taken as the answer. */
    public synchronized void questionAsked() {
        awaitingAnswer = true;
    }

    public synchronized boolean awaitingAnswer() {
        return awaitingAnswer;
    }

    /** Forgets everything, so a server joined next cannot use what the player started on the last one. */
    public synchronized void reset() {
        until = Instant.MIN;
        remaining = 0;
        awaitingAnswer = false;
        fresh = false;
        requestedOptions = 1;
    }

    /** Uses one call if any are left and the hourly ceiling allows it. */
    public synchronized boolean allow(Instant now) {
        while (!recent.isEmpty() && !recent.peekFirst().isAfter(now.minus(Duration.ofHours(1)))) {
            recent.removeFirst();
        }
        if (!now.isBefore(until) || remaining <= 0 || recent.size() >= HOURLY) {
            return false;
        }
        remaining--;
        recent.addLast(now);
        lastWasFirst = fresh;
        fresh = false;
        return true;
    }

    /** Whether the last allowed call was the first since the player started something (worth telling them about). */
    public synchronized boolean lastWasFirst() {
        return lastWasFirst;
    }

    /** Only the current local provider's activity may keep an existing grant alive; never refill its call budget. */
    public synchronized void activity(Instant now) {
        if (!fresh && remaining > 0 && !until.equals(Instant.MIN)) {
            Instant extended = now.plus(WINDOW);
            if (extended.isAfter(until)) until = extended;
        }
    }

    private void arm(Instant now) {
        until = now.plus(WINDOW);
        remaining = CALLS * requestedOptions;
        fresh = true;
    }

    /** Only commands that start an AI request (TypedRequests) arm the gate, plus "again", which reruns a typed one. */
    public static boolean startsAi(String command) {
        TypedRequests.Typed typed = TypedRequests.of(command);
        if (!typed.requests().isEmpty() || typed.answer() != null) return true;
        String[] words = command.strip().toLowerCase(Locale.ROOT).split("\\s+", 3);
        return words.length >= 3 && dev.justbuild.command.JustBuildCommand.isRoot(words[0]) && words[1].equals("again");
    }
}
