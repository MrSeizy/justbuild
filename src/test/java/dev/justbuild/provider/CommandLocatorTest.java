package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandLocatorTest {
    @TempDir
    Path dir;

    Path executable(Path p) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "#!/bin/sh\n");
        assertTrue(p.toFile().setExecutable(true));
        return p;
    }

    @Test
    void usesConfiguredPathWhenExecutable() throws Exception {
        Path exe = executable(dir.resolve("custom/claude"));
        assertEquals(exe, CommandLocator.resolve(exe.toString(), "claude", List.of()));
    }

    @Test
    void fallsBackToSearchDirs() throws Exception {
        Path exe = executable(dir.resolve("bin/codex"));
        assertEquals(exe, CommandLocator.resolve(dir.resolve("missing/codex").toString(), "codex",
                List.of(dir.resolve("nope"), dir.resolve("bin"))));
    }

    @Test
    void reportsEveryPathChecked() {
        var e = assertThrows(ProviderException.class, () -> CommandLocator.resolve("/x/claude", "claude",
                List.of(Path.of("/a"), Path.of("/x"))));
        assertTrue(e.getMessage().contains(Path.of("/x/claude") + ", " + Path.of("/a/claude")), e.getMessage());
        assertTrue(e.getMessage().contains("config/justbuild.json"));
    }

    @Test
    void searchesTheCodexBundledInsideTheChatGptApp() {
        assertTrue(CommandLocator.defaultSearchDirs().contains(
                java.nio.file.Path.of("/Applications/ChatGPT.app/Contents/Resources/codex-cli/bin")));
    }

    @Test
    void expandsHome() {
        String home = System.getProperty("user.home");
        assertEquals(Path.of(home, ".local/bin/claude"), CommandLocator.expandHome("~/.local/bin/claude"));
        assertEquals(Path.of("/opt/x"), CommandLocator.expandHome("/opt/x"));
    }
}
