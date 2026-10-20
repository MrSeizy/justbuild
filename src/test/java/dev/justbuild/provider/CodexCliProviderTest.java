package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexCliProviderTest {
    @TempDir
    Path dir;

    static final PlanRequest REQUEST = new PlanRequest("SYSTEM", "USER", "{\"type\":\"object\"}", "",
            Duration.ofSeconds(10));

    /** Pinned on purpose: removing any of these re-enables a tool that can read the player's files. */
    @Test
    void commandDisablesEveryTool() {
        Path executable = dir.resolve("codex");
        Path schema = dir.resolve("schema.json");
        Path output = dir.resolve("out.json");
        List<String> cmd = CodexCliProvider.command(executable, schema, output, "");
        assertEquals(List.of(executable.toString(), "exec", "--ephemeral", "--ignore-user-config", "--ignore-rules",
                "--skip-git-repo-check", "-s", "read-only",
                "--disable", "shell_tool", "--disable", "unified_exec", "--disable", "view_image",
                "--disable", "apps", "--disable", "plugins", "--disable", "multi_agent",
                "--disable", "browser_use", "--disable", "browser_use_external", "--disable", "computer_use",
                "--disable", "image_generation", "--disable", "hooks", "--disable", "skill_search",
                "--disable", "tool_suggest", "--disable", "code_mode_host", "--disable", "goals",
                "--disable", "sleep_tool", "--disable", "in_app_browser", "--disable", "workspace_dependencies",
                "--disable", "skill_mcp_dependency_install", "--disable", "remote_plugin",
                "-c", "web_search=\"disabled\"", "-c", "model_reasoning_summary=\"concise\"",
                "-c", "project_doc_max_bytes=0", "-c", "skills.include_instructions=false",
                "-c", "skills.bundled.enabled=false", "--enable", "skip_host_skill_discovery",
                "-c", "memories.use_memories=false", "-c", "memories.generate_memories=false",
                "-c", "tools.experimental_request_user_input.enabled=false", "--json",
                "--output-schema", schema.toString(), "-o", output.toString(), "-"), cmd);
        List<String> withModel = CodexCliProvider.command(executable, schema, output, "gpt-5.5");
        assertEquals(List.of("-m", "gpt-5.5", "-"), withModel.subList(withModel.size() - 3, withModel.size()));
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void readsTheOutputFile() throws Exception {
        Path fake = script("""
                #!/bin/sh
                out=""
                while [ $# -gt 0 ]; do
                  if [ "$1" = "-o" ]; then out="$2"; shift; fi
                  shift
                done
                cat > "$out.stdin"
                printf '%s' '{"kind":"build"}' > "$out"
                """);
        assertEquals("{\"kind\":\"build\"}", provider(fake).complete(REQUEST, new CancellationToken()));
    }

    @Test
    void refusesAReplyFileThatIsTooLarge() throws Exception {
        Path reply = dir.resolve("last-message.json");
        Files.writeString(reply, "x".repeat(2048));
        var e = assertThrows(ProviderException.class, () -> CodexCliProvider.readReply(reply, 1024));
        assertEquals("Codex wrote a reply larger than 1 KB, so JustBuild did not read it.", e.getMessage());
        Files.writeString(reply, "{\"kind\":\"build\"}");
        assertEquals("{\"kind\":\"build\"}", CodexCliProvider.readReply(reply, 1024));
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void reportsNonZeroExit() throws Exception {
        Path fake = script("""
                #!/bin/sh
                cat > /dev/null
                echo "noise" >&2
                echo "Error: not logged in" >&2
                exit 4
                """);
        var e = assertThrows(ProviderException.class, () -> provider(fake).complete(REQUEST, new CancellationToken()));
        assertEquals("Codex exited with code 4: noise\nError: not logged in", e.getMessage());
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void listsModelsWithDebugModels() throws Exception {
        Path fake = script("""
                #!/bin/sh
                [ "$1 $2" = "debug models" ] || exit 9
                echo '{"models":[{"slug":"b","visibility":"list","priority":2},{"slug":"a","visibility":"list","priority":1}]}'
                """);
        assertEquals(List.of("a", "b"), provider(fake).models(new CancellationToken()));
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void runsWithAnEmptyHomeAndAuthOnlyCodexHome() throws Exception {
        Path fake = script("""
                #!/bin/sh
                out=""
                while [ $# -gt 0 ]; do
                  if [ "$1" = "-o" ]; then out="$2"; shift; fi
                  shift
                done
                cat > /dev/null
                for step in 1 2 3 4 5 6; do
                  echo '{"type":"item.updated","item":{"type":"reasoning","text":"thinking"}}'
                  sleep 0.2
                done
                printf '{"home":"%s","codex":"%s"}' "$HOME" "$CODEX_HOME" > "$out"
                echo '{"type":"turn.completed","usage":{"input_tokens":5,"output_tokens":7}}'
                """);
        RecordingListener progress = new RecordingListener();
        var shortIdle = new PlanRequest("SYSTEM", "USER", "{}", "", Duration.ofMillis(600));
        String text = provider(fake).complete(shortIdle, new CancellationToken(), progress);
        var o = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
        String home = System.getProperty("user.home");
        assertNotEquals(home, o.get("home").getAsString(), "skills in ~/.agents must not be visible to Codex");
        assertTrue(o.get("home").getAsString().contains("justbuild-codex"));
        assertNotEquals(CodexCliProvider.codexHome(), o.get("codex").getAsString());
        assertTrue(o.get("codex").getAsString().endsWith("/codex"));
        assertArrayEquals(new long[] {5, 7}, progress.tokens.get(0));
        assertTrue(progress.activities.get() >= 3, "unrecognized Codex events did not forward activity");
    }

    @Test
    void linksOnlyTheExistingLoginAndRefreshesItWithoutCopying() throws Exception {
        Path login = dir.resolve("login");
        Files.createDirectory(login);
        Path auth = login.resolve("auth.json");
        Files.writeString(auth, "SYNTHETIC LOGIN");
        for (String name : List.of("AGENTS.md", "AGENTS.override.md", "config.toml")) {
            Files.writeString(login.resolve(name), "PRIVATE INSTRUCTIONS");
        }
        Files.createDirectories(login.resolve("skills"));
        Files.createDirectories(login.resolve("memories"));
        Path work = dir.resolve("work");
        Path isolated = Path.of(CodexCliProvider.isolatedHome(work, login).get("CODEX_HOME"));
        try (var entries = Files.list(isolated)) {
            assertEquals(List.of("auth.json"), entries.map(p -> p.getFileName().toString()).toList());
        }
        assertTrue(Files.isSameFile(auth, isolated.resolve("auth.json")));
        // Codex 0.162.1 saves file-backed refreshes by truncating this path, following the link.
        Files.writeString(isolated.resolve("auth.json"), "SYNTHETIC REFRESH");
        assertEquals("SYNTHETIC REFRESH", Files.readString(auth));
        Files.delete(auth);
        assertFalse(Files.exists(isolated.resolve("auth.json")), "logout must invalidate the linked login");
        Files.writeString(auth, "SYNTHETIC REFRESH");
        TempDirs.delete(work);
        assertEquals("SYNTHETIC REFRESH", Files.readString(auth), "cleaning the view must not remove the real login");
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void doctorChecksTheSameIsolatedLoginAsBuilds() throws Exception {
        Path fake = script("""
                #!/bin/sh
                [ "$1 $2" = "login status" ] || exit 9
                case "$CODEX_HOME" in */codex) echo 'Logged in';; *) exit 8;; esac
                """);
        assertTrue(provider(fake).check(new CancellationToken()).contains("Logged in"));
    }

    private Path script(String body) throws Exception {
        Path fake = dir.resolve("codex");
        Files.writeString(fake, body);
        assertTrue(fake.toFile().setExecutable(true));
        return fake;
    }

    private CodexCliProvider provider(Path exe) {
        return new CodexCliProvider(exe.toString(), List.of(), dir.resolve("login"));
    }
}
