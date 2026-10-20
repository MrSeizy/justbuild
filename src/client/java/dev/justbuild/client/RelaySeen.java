package dev.justbuild.client;

import dev.justbuild.relay.PromptCheck;
import dev.justbuild.relay.TypedRequests;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What this game has seen, for checking a server's AI requests (relay.PromptCheck): the requests the player started
 * with /justbuild (this session, plus the ones they typed earlier on this computer), the questions this game's own AI
 * asked, the player's answers, and the plans its AI returned. Client thread only; the session sets clear when joining
 * or leaving a server.
 */
final class RelaySeen {
    private static final int KEEP = 20;
    private static final Set<String> requests = new LinkedHashSet<>();
    private static final Set<String> questions = new LinkedHashSet<>();
    private static final Set<String> answers = new LinkedHashSet<>();
    private static final Set<String> plans = new LinkedHashSet<>();

    private RelaySeen() {
    }

    /** An outgoing command (without the slash): remembers what an AI-starting /justbuild asks for. */
    static void command(String command) {
        TypedRequests.Typed typed = TypedRequests.of(command);
        for (String request : typed.requests()) {
            add(requests, request);
            RequestHistory.add(request);
        }
        add(answers, typed.answer());
    }

    /** Chat sent while this game's AI is waiting for an answer. */
    static void answer(String message) {
        add(answers, message);
    }

    /** A question this game's AI asked (as the server will quote it back). */
    static void question(String question) {
        add(questions, question);
    }

    /** A plan this game's AI returned ({@link PromptCheck#planKey}), which a review or shrink may carry back. */
    static void plan(String key) {
        add(plans, key);
    }

    static PromptCheck.Seen snapshot() {
        Set<String> all = new LinkedHashSet<>(RequestHistory.all());
        all.addAll(requests);
        return new PromptCheck.Seen(Set.copyOf(all), Set.copyOf(questions), Set.copyOf(answers), Set.copyOf(plans));
    }

    static void clear() {
        requests.clear();
        questions.clear();
        answers.clear();
        plans.clear();
    }

    private static void add(Set<String> set, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        set.remove(text.strip());
        set.add(text.strip());
        while (set.size() > KEEP) {
            set.remove(set.iterator().next());
        }
    }
}
