package dev.justbuild.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

final class TempDirs {
    private TempDirs() {
    }

    static Path create(String prefix) throws ProviderException {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new ProviderException("Could not create a temp directory: " + e.getMessage());
        }
    }

    static void delete(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort
        }
    }
}
