package dev.justbuild.session;

import dev.justbuild.prompt.Exchange;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One build request and its clarifying questions. Not thread-safe; used on the server thread. */
public final class Conversation {
    public static final Duration QUESTION_TTL = Duration.ofMinutes(10);

    private final String request;
    private final List<Exchange> exchanges = new ArrayList<>();
    private String pendingQuestion;
    private List<String> pendingOptions = List.of();
    private Instant askedAt;

    public Conversation(String request) {
        this.request = request;
    }

    public String request() {
        return request;
    }

    public List<Exchange> exchanges() {
        return List.copyOf(exchanges);
    }

    public boolean hasPendingQuestion() {
        return pendingQuestion != null;
    }

    public String pendingQuestion() {
        return pendingQuestion;
    }

    public List<String> pendingOptions() {
        return pendingOptions;
    }

    public int questionsRemaining(int maxQuestions) {
        int asked = exchanges.size() + (pendingQuestion == null ? 0 : 1);
        return Math.max(0, maxQuestions - asked);
    }

    public void ask(String question, List<String> options, Instant now) {
        if (pendingQuestion != null) {
            throw new IllegalStateException("a question is already pending");
        }
        pendingQuestion = question;
        pendingOptions = List.copyOf(options);
        askedAt = now;
    }

    public void answer(String answer) {
        if (pendingQuestion == null) {
            throw new IllegalStateException("no question is pending");
        }
        exchanges.add(new Exchange(pendingQuestion, answer.trim()));
        pendingQuestion = null;
        pendingOptions = List.of();
        askedAt = null;
    }

    public boolean isExpired(Instant now) {
        return pendingQuestion != null && now.isAfter(askedAt.plus(QUESTION_TTL));
    }
}
