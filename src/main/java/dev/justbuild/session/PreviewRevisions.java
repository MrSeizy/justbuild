package dev.justbuild.session;

import dev.justbuild.plan.BuildPlan;
import dev.justbuild.relay.PromptCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A waiting preview's AI revisions: the request before the first one, the changes asked for since, and up to
 * {@link #MAX_EARLIER} earlier versions that Previous brings back without another AI call. {@code count} keeps counting
 * revisions whose versions were dropped. Session-only: a saved preview keeps just its latest version.
 */
public record PreviewRevisions(String original, List<String> changes, List<Earlier> earlier, int count) {
    public static final int MAX_EARLIER = 10;
    /** Stored descriptions stay well inside PreviewStore's request limit however often a plan is revised. */
    public static final int MAX_DESCRIPTION = 16_384;
    public static final PreviewRevisions NONE = new PreviewRevisions(null, List.of(), List.of(), 0);

    /** A version the preview showed before a revision. Plans stay compact; Previous rebuilds the blocks. */
    public record Earlier(BuildPlan plan, String title, String summary, String description) {
        public Earlier {
            Objects.requireNonNull(plan);
            Objects.requireNonNull(description);
        }
    }

    /** What a revision tells the AI about the preview's past, clipped to what a player's game accepts. */
    public record History(String original, List<String> earlier) { }

    public PreviewRevisions {
        changes = List.copyOf(changes);
        earlier = List.copyOf(earlier);
        if (changes.size() != earlier.size() || earlier.size() > MAX_EARLIER || count < earlier.size())
            throw new IllegalArgumentException("Invalid preview revisions");
    }

    /** {@code current} is the version this revision replaces; {@code change} is what the player asked for. */
    public PreviewRevisions after(String change, Earlier current) {
        List<String> nextChanges = new ArrayList<>(changes);
        nextChanges.add(change);
        List<Earlier> nextEarlier = new ArrayList<>(earlier);
        nextEarlier.add(current);
        while (nextEarlier.size() > MAX_EARLIER) {
            nextEarlier.removeFirst();
            nextChanges.removeFirst();
        }
        return new PreviewRevisions(original != null ? original : current.description(), nextChanges, nextEarlier, count + 1);
    }

    public boolean hasPrevious() {
        return !earlier.isEmpty();
    }

    /** The version Previous brings back. */
    public Earlier latest() {
        if (earlier.isEmpty()) throw new IllegalStateException("No earlier version");
        return earlier.getLast();
    }

    /** This history once Previous has brought back {@link #latest()}. */
    public PreviewRevisions previous() {
        if (earlier.isEmpty()) throw new IllegalStateException("No earlier version");
        return new PreviewRevisions(original, changes.subList(0, changes.size() - 1),
                earlier.subList(0, earlier.size() - 1), count - 1);
    }

    /** The stored request of a revised version: the current description plus the change (and any answers). */
    public static String description(String current, String change) {
        String next = current + " (revised: " + change + ")";
        return next.length() <= MAX_DESCRIPTION ? next : next.substring(0, MAX_DESCRIPTION - 1) + "…";
    }

    /** {@code currentDescription} stands in for the original until the preview's first revision. */
    public History history(String currentDescription) {
        return new History(clip(original != null ? original : currentDescription),
                changes.stream().map(PreviewRevisions::clip).toList());
    }

    private static String clip(String text) {
        int max = PromptCheck.MAX_HISTORY_TEXT;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
