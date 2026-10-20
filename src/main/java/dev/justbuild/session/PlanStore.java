package dev.justbuild.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Saves every raw AI plan under <game dir>/justbuild/plans for debugging, keeping the newest {@link #KEEP}. */
public final class PlanStore {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    static final int KEEP = 500;

    private final Path dir;
    private final Clock clock;
    private final int keep;

    public PlanStore(Path dir) {
        this(dir, Clock.systemDefaultZone());
    }

    PlanStore(Path dir, Clock clock) {
        this(dir, clock, KEEP);
    }

    PlanStore(Path dir, Clock clock, int keep) {
        this.dir = dir;
        this.clock = clock;
        this.keep = keep;
    }

    public Path save(String raw, String title) throws IOException {
        return write(slug(title), raw);
    }

    public Path saveInvalid(String raw) throws IOException {
        return write("invalid", raw);
    }

    /** Two AI workers may save at once: each name is claimed with CREATE_NEW, so neither overwrites the other. */
    private synchronized Path write(String name, String raw) throws IOException {
        Files.createDirectories(dir);
        String base = STAMP.format(LocalDateTime.now(clock)) + "-" + name;
        Path file = dir.resolve(base + ".json");
        for (int n = 2; ; n++) {
            try {
                Files.writeString(file, raw == null ? "" : raw, java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE);
                break;
            } catch (java.nio.file.FileAlreadyExistsException e) {
                file = dir.resolve(base + "-" + n + ".json");
            }
        }
        prune();
        return file;
    }

    /** Deletes the oldest saved plans beyond {@code keep} (by time written, then name). */
    private void prune() {
        try (var files = Files.list(dir)) {
            java.util.List<Path> plans = files.filter(f -> f.getFileName().toString().endsWith(".json"))
                    .sorted(java.util.Comparator.comparing(PlanStore::written).thenComparing(PlanStore::order)).toList();
            for (int i = 0; i < plans.size() - keep; i++) {
                Files.deleteIfExists(plans.get(i));
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            // Only a debugging folder: a failed cleanup is retried on the next save.
        }
    }

    private static java.nio.file.attribute.FileTime written(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** "…-cabin.json" before "…-cabin-2.json" before "…-cabin-10.json". */
    private static String order(Path file) {
        String name = file.getFileName().toString();
        var m = java.util.regex.Pattern.compile("^(.*?)(?:-(\\d+))?\\.json$").matcher(name);
        return m.matches() ? m.group(1) + String.format("%06d", m.group(2) == null ? 1 : Integer.parseInt(m.group(2))) : name;
    }

    static String slug(String title) {
        String s = title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.length() > 40) {
            s = s.substring(0, 40).replaceAll("-+$", "");
        }
        return s.isEmpty() ? "build" : s;
    }
}
