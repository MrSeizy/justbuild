package dev.justbuild.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Self-review calls for test AIs. With AI review "When needed", a draft the checks flag gets one review call; tests
 * that count plan calls answer it here with the draft unchanged, which is what a review returns for a good plan.
 */
final class StubReview {
    private static final String MARKER = "reviewing your own plan", START = "Current plan:\n", END = "\n\nViews of the plan:";

    private StubReview() { }

    /** The draft under review, to send back unchanged, or null when {@code body} asks for a plan. */
    static String draft(String body) {
        if (!body.contains(MARKER)) return null;
        var messages = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("messages");
        JsonElement content = messages.get(messages.size() - 1).getAsJsonObject().get("content");
        // With images attached, the text is the first part of the content.
        String text = content.isJsonArray() ? content.getAsJsonArray().get(0).getAsJsonObject().get("text").getAsString()
                : content.getAsString();
        int start = text.indexOf(START, text.indexOf(MARKER)), end = start < 0 ? -1 : text.indexOf(END, start);
        if (end < 0) throw new IllegalStateException("A review request did not include its draft");        return text.substring(start + START.length(), end);
    }
}
