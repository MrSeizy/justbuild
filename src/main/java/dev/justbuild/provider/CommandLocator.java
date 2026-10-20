package dev.justbuild.provider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Finds CLI binaries; a Prism-launched game does not inherit the shell PATH. */
public final class CommandLocator {
    private CommandLocator() {
    }

    public static List<Path> defaultSearchDirs() {
        String home = System.getProperty("user.home");
        return List.of(Path.of(home, ".local/bin"), Path.of("/opt/homebrew/bin"), Path.of("/usr/local/bin"),
                Path.of(home, ".npm-global/bin"),
                // The ChatGPT desktop app ships a working Codex CLI that shares its ChatGPT login.
                Path.of("/Applications/ChatGPT.app/Contents/Resources/codex-cli/bin"),
                Path.of(home, "Applications/ChatGPT.app/Contents/Resources/codex-cli/bin"));
    }

    public static Path expandHome(String path) {
        String home = System.getProperty("user.home");
        if (path.equals("~")) {
            return Path.of(home);
        }
        if (path.startsWith("~/")) {
            return Path.of(home, path.substring(2));
        }
        return Path.of(path);
    }

    public static Path resolve(String configured, String name, List<Path> searchDirs) throws ProviderException {
        Set<Path> tried = new LinkedHashSet<>();
        if (configured != null && !configured.isBlank()) {
            Path p = expandHome(configured.trim());
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p;
            }
            tried.add(p);
        }
        for (Path dir : searchDirs) {
            Path p = dir.resolve(name);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p;
            }
            tried.add(p);
        }
        throw new ProviderException("Could not find the " + name + " CLI. Checked: "
                + tried.stream().map(Path::toString).collect(Collectors.joining(", "))
                + ". Install it, or set its path in config/justbuild.json; Settings → Doctor checks it.");
    }
}
