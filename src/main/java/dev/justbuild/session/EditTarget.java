package dev.justbuild.session;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** "#0a1b2c3d add lighting" → build id + change; without "#id" the target is picked by crosshair or recency. */
public record EditTarget(Optional<String> buildId, String change) {
    private static final Pattern ID = Pattern.compile("^#([0-9a-f]{8})(?:\\s+(.*))?$", Pattern.DOTALL);

    public static EditTarget parse(String text) {
        String trimmed = text.strip();
        Matcher m = ID.matcher(trimmed);
        if (m.matches()) {
            return new EditTarget(Optional.of(m.group(1)), m.group(2) == null ? "" : m.group(2).strip());
        }
        return new EditTarget(Optional.empty(), trimmed);
    }

    /** A stored request without its "(direction: …)" note, so "again" can draw a fresh one. */
    static String withoutDirection(String request) {
        return request.replaceAll(" \\(direction: [^)]*\\)", "").strip();
    }
}
