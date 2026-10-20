package dev.justbuild.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Uses the logged-in `codex` CLI (ChatGPT subscription) headless, with every tool disabled. */
public final class CodexCliProvider implements BuildProvider {
    /** `-s read-only` still lets the shell read any file, so every tool feature is switched off. */
    static final List<String> DISABLED_FEATURES = List.of("shell_tool", "unified_exec", "view_image", "apps",
            "plugins", "multi_agent", "browser_use", "browser_use_external", "computer_use", "image_generation",
            "hooks", "skill_search", "tool_suggest", "code_mode_host", "goals", "sleep_tool", "in_app_browser",
            "workspace_dependencies", "skill_mcp_dependency_install", "remote_plugin");

    private final String command;
    private final List<Path> searchDirs;
    private final Path credentialHome;

    /** {@code command} is the configured path (codex.command); blank or missing means search the usual places. */
    public CodexCliProvider(String command) {
        this(command, CommandLocator.defaultSearchDirs());
    }

    CodexCliProvider(String command, List<Path> searchDirs) {
        this(command, searchDirs, Path.of(codexHome()));
    }

    CodexCliProvider(String command, List<Path> searchDirs, Path credentialHome) {
        this.command = command;
        this.searchDirs = searchDirs;
        this.credentialHome = credentialHome.toAbsolutePath().normalize();
    }

    @Override
    public String id() {
        return "codex";
    }

    @Override
    public String displayName() {
        return "Codex CLI";
    }

    static List<String> command(Path exe, Path schemaFile, Path outputFile, String model) {
        List<String> cmd = new ArrayList<>(List.of(exe.toString(), "exec", "--ephemeral", "--ignore-user-config",
                "--ignore-rules", "--skip-git-repo-check", "-s", "read-only"));
        for (String feature : DISABLED_FEATURES) {
            cmd.add("--disable");
            cmd.add(feature);
        }
        // --json streams events (reasoning summaries, usage) on stdout; the plan still goes to the -o file.
        cmd.addAll(List.of("-c", "web_search=\"disabled\"", "-c", "model_reasoning_summary=\"concise\"",
                "-c", "project_doc_max_bytes=0", "-c", "skills.include_instructions=false",
                "-c", "skills.bundled.enabled=false", "--enable", "skip_host_skill_discovery",
                "-c", "memories.use_memories=false", "-c", "memories.generate_memories=false",
                "-c", "tools.experimental_request_user_input.enabled=false", "--json",
                "--output-schema", schemaFile.toString(), "-o", outputFile.toString()));
        if (model != null && !model.isBlank()) {
            cmd.add("-m");
            cmd.add(model.trim());
        }
        cmd.add("-");
        return cmd;
    }

    @Override
    public String complete(PlanRequest request, CancellationToken token, ProgressListener progress)
            throws ProviderException {
        CliModel.require(request.model());
        Path exe = CommandLocator.resolve(command, "codex", searchDirs);
        Path work = TempDirs.create("justbuild-codex");
        try {
            Path schema = work.resolve("schema.json");
            Path output = work.resolve("last-message.json");
            write(schema, request.schemaJson());
            List<String> args = command(exe, schema, output, request.model());
            int imageIndex = 0;
            for (byte[] png : request.images()) {
                Path path = work.resolve("view-" + imageIndex++ + ".png");
                try { Files.write(path, png); }
                catch (IOException e) { throw new ProviderException("Could not prepare a planning image."); }
                args.addAll(args.size() - 1, List.of("--image", path.toString()));
            }
            CodexStream stream = new CodexStream(progress);
            ProcessResult r = ProcessRunner.runWithIdleTimeout(args,
                    request.systemPrompt() + "\n\n" + request.userContent(), work, request.timeout(), token,
                    stream::accept, isolatedHome(work, credentialHome), progress::activity);
            if (r.exitCode() != 0) {
                String why = stream.failure() != null ? stream.failure() : ProcessRunner.tail(r.stderr(), 3);
                throw new ProviderException("Codex exited with code " + r.exitCode() + ": " + why);
            }
            String text = Files.exists(output) ? readReply(output, OutputLimit.MAX_REPLY_FILE_BYTES) : "";
            if (text.isBlank()) {
                throw new ProviderException("Codex returned no message");
            }
            return text;
        } finally {
            TempDirs.delete(work);
        }
    }

    @Override
    public String check(CancellationToken token) throws ProviderException {
        Path exe = CommandLocator.resolve(command, "codex", searchDirs);
        Path work = TempDirs.create("justbuild-codex");
        try {
            ProcessResult r = ProcessRunner.run(List.of(exe.toString(), "login", "status"), "", work,
                    Duration.ofSeconds(30), token, null, isolatedHome(work, credentialHome));
            String status = ProcessRunner.tail(r.stdout() + "\n" + r.stderr(), 1);
            if (r.exitCode() != 0) {
                throw new ProviderException("Could not use your existing Codex login (" + status
                        + "). JustBuild supports file-backed Codex logins and auth environment variables; "
                        + "keyring-only logins cannot be isolated. Select another provider if needed.");
            }
            return exe + ", " + status;
        } finally {
            TempDirs.delete(work);
        }
    }

    /**
     * The ordinary CODEX_HOME injects global AGENTS.md even with --ignore-user-config and project_doc_max_bytes=0.
     * Give the CLI an auth-only view of its existing login: the link keeps CLI token refreshes on the original file,
     * without copying credentials or requiring another login. All other user context stays outside CODEX_HOME.
     * HOME is also empty so personal ~/.agents skills cannot be discovered. Never fall back to the ordinary login.
     */
    static Map<String, String> isolatedHome(Path work, Path credentialHome) throws ProviderException {
        Path home = work.resolve("home");
        Path isolated = work.resolve("codex");
        try {
            Files.createDirectories(home);
            try {
                Files.createDirectories(isolated, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
            } catch (UnsupportedOperationException e) {
                Files.createDirectories(isolated);
            }
            Path auth = credentialHome.resolve("auth.json");
            if (Files.exists(auth, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Path target = auth.toAbsolutePath().normalize();
                // Do not copy or hard-link: logout/account replacement must affect this view immediately too.
                Files.createSymbolicLink(isolated.resolve("auth.json"), target);
            }
        } catch (IOException | UnsupportedOperationException e) {
            throw new ProviderException("Could not prepare a folder for Codex: " + e.getMessage());
        }
        return Map.of("HOME", home.toString(), "CODEX_HOME", isolated.toString());
    }

    /** The existing login's home. Only auth.json is linked into each request's otherwise empty home. */
    static String codexHome() {
        String configured = System.getenv("CODEX_HOME");
        Path base = configured != null && !configured.isBlank() ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".codex");
        return base.toAbsolutePath().normalize().toString();
    }

    /** `codex debug models` prints the catalog the Codex model picker uses; it runs no model. */
    @Override
    public List<String> models(CancellationToken token) throws ProviderException {
        Path exe = CommandLocator.resolve(command, "codex", searchDirs);
        Path work = TempDirs.create("justbuild-codex");
        try {
            ProcessResult r = ProcessRunner.run(List.of(exe.toString(), "debug", "models"), "", work,
                    Duration.ofSeconds(30), token, null, isolatedHome(work, credentialHome));
            if (r.exitCode() != 0) {
                throw new ProviderException("codex debug models failed: " + ProcessRunner.tail(r.stderr() + r.stdout(), 2));
            }
            return ModelCatalog.codex(r.stdout());
        } finally {
            TempDirs.delete(work);
        }
    }

    private static void write(Path file, String text) throws ProviderException {
        try {
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new ProviderException("Could not write " + file + ": " + e.getMessage());
        }
    }

    /** The final message Codex wrote, refused past {@code maxBytes} instead of being read into memory whole. */
    static String readReply(Path file, long maxBytes) throws ProviderException {
        try {
            if (Files.size(file) > maxBytes) {
                throw new ProviderException("Codex wrote a reply larger than " + OutputLimit.describe(maxBytes)
                        + ", so JustBuild did not read it.");
            }
        } catch (IOException e) {
            throw new ProviderException("Could not read " + file + ": " + e.getMessage());
        }
        return read(file);
    }

    private static String read(Path file) throws ProviderException {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new ProviderException("Could not read " + file + ": " + e.getMessage());
        }
    }
}
