package dev.justbuild.plan;

/** Pulls the outermost JSON object out of text that may be wrapped in Markdown fences or prose. */
public final class JsonText {
    private JsonText() {
    }

    public static String extractObject(String text) throws PlanException {
        if (text == null || text.isBlank()) {
            throw new PlanException("the response was empty");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new PlanException("the response contains no JSON object");
        }
        return text.substring(start, end + 1);
    }
}
