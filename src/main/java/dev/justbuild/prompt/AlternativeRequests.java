package dev.justbuild.prompt;

/** Shared expansion of an explicit request for alternatives, so the relay recognizes only authorized variants. */
public final class AlternativeRequests {
    private static final String[] DIRECTIONS = {
            "Prefer a balanced, compact arrangement and a restrained palette.",
            "Explore a different arrangement of volumes and a contrasting, coherent palette.",
            "Explore a distinct silhouette and details while keeping the requested scale."
    };
    private AlternativeRequests() { }

    public static String variant(String request, int option, int count) {
        if (count < 2 || count > 3 || option < 1 || option > count) throw new IllegalArgumentException("Choose two or three options.");
        return "Design option " + option + " of " + count + ". " + DIRECTIONS[option - 1]
                + " Follow all constraints in the player's request, which take priority: " + request.strip();
    }

    /** The command tail after 'alternatives': build|edit, count, and request text. */
    public record Command(boolean edit, int count, String text) { }
    public static Command parse(String tail) {
        String[] words = tail.strip().split("\\s+", 3);
        if (words.length != 3 || !words[0].equals("build") && !words[0].equals("edit")) {
            throw new IllegalArgumentException("Use alternatives build|edit 2|3 <request>.");
        }
        int count = Integer.parseInt(words[1]);
        if (count < 2 || count > 3 || words[2].isBlank()) throw new IllegalArgumentException("Choose two or three options.");
        return new Command(words[0].equals("edit"), count, words[2]);
    }
}
