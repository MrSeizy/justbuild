package dev.justbuild.session;

import dev.justbuild.provider.ProgressListener.Phase;
import java.time.Duration;

/** Wording for progress updates in chat and the action bar. */
final class StatusText {
    static final String BAR = "JustBuild · ";

    private StatusText() {
    }

    static String elapsed(Duration d) {
        long s = d.toSeconds();
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    static String activity(String provider, Phase phase, int operations) {
        return activity(provider, phase, operations, 0, 0, false, null);
    }

    static String activity(String provider, Phase phase, int operations, int round, int rounds) {
        return activity(provider, phase, operations, round, rounds, false, null);
    }

    static String activity(String provider, Phase phase, int operations, int round, int rounds, boolean shrinking) {
        return activity(provider, phase, operations, round, rounds, shrinking, null);
    }

    /**
     * What the AI is doing. {@code round > 0} is a self-review round, or a smaller-plan try when {@code shrinking};
     * {@code note} is the backend's latest headline (Codex reasoning summaries), shown after the activity.
     */
    static String activity(String provider, Phase phase, int operations, int round, int rounds, boolean shrinking,
            String note) {
        String doing;
        if (round > 0) {
            doing = provider + (shrinking ? " is making its plan smaller (try " : " is reviewing its plan (round ")
                    + round + " of " + rounds + ")";
        } else {
            doing = switch (phase) {
                case WAITING -> provider + " is planning";
                case THINKING -> provider + " is thinking";
                case WRITING -> provider + " is writing the plan";
            };
        }
        if (note != null && !note.isBlank()) {
            doing += ": " + note.strip();
        }
        boolean counting = round > 0 || phase == Phase.WRITING;
        return doing + (!counting ? "" : operations == 1 ? ", 1 operation so far"
                : operations > 1 ? ", " + operations + " operations so far" : "");
    }

    static String heartbeat(String provider, Phase phase, int operations, Duration elapsed) {
        return heartbeat(provider, phase, operations, 0, 0, false, null, elapsed);
    }

    static String heartbeat(String provider, Phase phase, int operations, int round, int rounds, Duration elapsed) {
        return heartbeat(provider, phase, operations, round, rounds, false, null, elapsed);
    }

    static String heartbeat(String provider, Phase phase, int operations, int round, int rounds, boolean shrinking,
            String note, Duration elapsed) {
        return "Still working (" + elapsed(elapsed) + "): " + activity(provider, phase, operations, round, rounds,
                shrinking, note) + ".";
    }

    static String waitingBar(String provider, Phase phase, int operations, Duration elapsed) {
        return waitingBar(provider, phase, operations, 0, 0, false, null, elapsed);
    }

    static String waitingBar(String provider, Phase phase, int operations, int round, int rounds, Duration elapsed) {
        return waitingBar(provider, phase, operations, round, rounds, false, null, elapsed);
    }

    static String waitingBar(String provider, Phase phase, int operations, int round, int rounds, boolean shrinking,
            String note, Duration elapsed) {
        return BAR + activity(provider, phase, operations, round, rounds, shrinking, note) + " · " + elapsed(elapsed);
    }

    /** "AI 0:48, 23.4k tokens in, 1.9k out over 3 calls"; tokens are left out when the backend reported none. */
    static String aiSummary(Duration aiTime, long tokensIn, long tokensOut, int calls) {
        String s = "AI " + elapsed(aiTime);
        if (tokensIn > 0 || tokensOut > 0) {
            s += ", " + tokens(tokensIn) + " tokens in, " + tokens(tokensOut) + " out";
            if (calls > 1) {
                s += " over " + calls + " calls";
            }
        }
        return s;
    }

    static String tokens(long n) {
        if (n < 1000) {
            return Long.toString(n);
        }
        if (n < 1_000_000) {
            return String.format(java.util.Locale.ROOT, "%.1fk", n / 1000.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0);
    }

    static String placingBar(String title, int placed, int total, boolean refreshing) {
        if (refreshing) {
            return BAR + "Finishing “" + title + "” (connecting stairs and fences)";
        }
        int percent = total == 0 ? 100 : (int) ((long) placed * 100 / total);
        return BAR + "Placing “" + title + "” " + percent + "% (" + placed + "/" + total + ")";
    }

    /** The highest quarter mark (25, 50, 75) reached at or past {@code next}, or -1. */
    static int milestone(int placed, int total, int next) {
        if (total <= 0) {
            return -1;
        }
        int mark = (int) ((long) placed * 100 / total) / 25 * 25;
        return mark >= next && mark > 0 && mark < 100 ? mark : -1;
    }
}
