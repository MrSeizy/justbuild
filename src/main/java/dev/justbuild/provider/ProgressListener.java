package dev.justbuild.provider;

/** Live progress from a backend while it works. Called from a worker thread; implementations must be thread-safe. */
public interface ProgressListener {
    /** WAITING: no detail yet; THINKING: reasoning; WRITING: emitting the plan. */
    enum Phase { WAITING, THINKING, WRITING }

    ProgressListener NONE = new ProgressListener() {
        @Override
        public void phase(Phase phase) {
        }

        @Override
        public void operations(int count) {
        }
    };

    void phase(Phase phase);

    /** Operations written so far in the streamed plan. */
    void operations(int count);

    /** Actual provider output arrived, even if it did not change the visible phase or operation count. */
    default void activity() {
    }

    /** A short headline of what the AI is working on now (Codex reasoning summaries). */
    default void note(String headline) {
    }

    /** Tokens one AI call used, reported once when it finishes; 0 when the backend did not say. */
    default void tokens(long input, long output) {
    }

    /** A one-off message for the player, such as a subscription limit warning; {@code key} lets repeats be dropped. */
    default void notice(String key, String message) {
    }

    /** Counts "op" keys in text appended after {@code from}; a key split across chunks is counted once it completes. */
    static int countOps(CharSequence text, int from) {
        int count = 0;
        for (int i = Math.max(0, from); i + 4 <= text.length(); i++) {
            if (text.charAt(i) == '"' && text.charAt(i + 1) == 'o' && text.charAt(i + 2) == 'p' && text.charAt(i + 3) == '"') {
                count++;
            }
        }
        return count;
    }
}
