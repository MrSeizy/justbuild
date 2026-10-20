package dev.justbuild.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Grok Build's subscription CLI, with a temporary context and no executable model tools. */
public final class GrokCliProvider implements BuildProvider {
    // Grok 1.0.52 treats an empty --tools list as its default toolset. Explicit removal is required.
    static final String DISALLOWED_TOOLS = "run_terminal_command,read_file,search_replace,list_dir,grep,todo_write,"
            + "monitor,search_tool,use_tool,workflow,enter_plan_mode,exit_plan_mode,ask_user_question,send_feedback,"
            + "image_gen,image_edit,image_to_video,reference_to_video,write,update_goal,create_goal,Agent,"
            + "ask_user,web_search,web_fetch";
    static final String CONFIG = """
            [cli]
            auto_update = false
            [session]
            load_envrc = false
            [toolset.bash]
            login_shell_capture = false
            [memory]
            enabled = false
            [subagents]
            enabled = false
            [plugins]
            enabled = []
            """;
    private final String command;
    private final List<Path> searchDirs;
    private final Path authFile;

    public GrokCliProvider(String command) { this(command, CommandLocator.defaultSearchDirs(), authFile()); }
    GrokCliProvider(String command, List<Path> searchDirs, Path authFile) {
        this.command = command; this.searchDirs = searchDirs; this.authFile = authFile.toAbsolutePath().normalize();
    }
    @Override public String id() { return "grok"; }
    @Override public String displayName() { return "Grok Build CLI"; }

    static List<String> command(Path exe, PlanRequest request, Path prompt) {
        var args = new ArrayList<>(List.of(exe.toString(), "--prompt-file", prompt.toString(), "--verbatim",
                "--system-prompt-override", request.systemPrompt(), "--json-schema", request.schemaJson(),
                "--output-format", "json", "--tools", "none", "--disallowed-tools", DISALLOWED_TOOLS,
                "--disable-web-search", "--no-subagents", "--no-plan", "--max-turns", "1",
                "--permission-mode", "dontAsk", "--deny", "*"));
        if (request.model() != null && !request.model().isBlank()) args.addAll(List.of("--model", request.model().trim()));
        return args;
    }

    @Override public String complete(PlanRequest request, CancellationToken token, ProgressListener progress) throws ProviderException {
        CliModel.require(request.model());
        Path exe = CommandLocator.resolve(command, "grok", searchDirs), work = TempDirs.create("justbuild-grok");
        try {
            var env = isolatedContext(work, authFile);
            Path prompt = work.resolve("prompt.json");
            Files.writeString(prompt, prompt(request));
            progress.phase(ProgressListener.Phase.WAITING);
            ProcessResult result = ProcessRunner.runWithIdleTimeout(command(exe, request, prompt), "", work.resolve("work"),
                    request.timeout(), token, null, env, progress::activity);
            String text = extract(result, progress);
            progress.phase(ProgressListener.Phase.WRITING);
            progress.operations(ProgressListener.countOps(text, 0));
            return text;
        } catch (IOException e) { throw new ProviderException("Could not prepare the Grok Build request."); }
        finally { TempDirs.delete(work); }
    }

    /** ACP content blocks in a file keep long prompts and images out of process arguments. */
    static String prompt(PlanRequest request) {
        var blocks = new JsonArray(); var words = new JsonObject();
        words.addProperty("type", "text"); words.addProperty("text", request.userContent()); blocks.add(words);
        for (byte[] png : request.images()) {
            var image = new JsonObject(); image.addProperty("type", "image"); image.addProperty("mimeType", "image/png");
            image.addProperty("data", java.util.Base64.getEncoder().encodeToString(png)); blocks.add(image);
        }
        return blocks.toString();
    }

    static String extract(ProcessResult result, ProgressListener progress) throws ProviderException {
        if (result.exitCode() != 0) throw failure(result);
        try {
            JsonObject root = JsonParser.parseString(result.stdout()).getAsJsonObject();
            if (!root.has("stopReason") || !root.get("stopReason").getAsString().equals("end_turn")
                    || root.has("structuredOutputError") || !(root.get("structuredOutput") instanceof JsonObject output))
                throw new ProviderException("Grok Build did not return a complete JSON response. Check the model and retry.");
            if (root.get("usage") instanceof JsonObject usage)
                progress.tokens(tokens(usage, "input_tokens"), tokens(usage, "output_tokens"));
            return output.toString();
        } catch (RuntimeException e) { throw new ProviderException("Grok Build returned an unreadable response. Update the CLI and retry."); }
    }
    private static long tokens(JsonObject usage, String name) {
        try { return Math.max(0, Math.min(1_000_000_000, usage.get(name).getAsLong())); }
        catch (RuntimeException e) { return 0; }
    }
    private static ProviderException failure(ProcessResult result) {
        String text = (result.stdout() + "\n" + result.stderr()).toLowerCase(java.util.Locale.ROOT);
        if (text.contains("auth") || text.contains("log in") || text.contains("login"))
            return new ProviderException("Grok Build is not logged in. Run `grok login` on this computer, then retry.");
        if (text.contains("unexpected argument") || text.contains("unknown option"))
            return new ProviderException("Grok Build is too old for the required isolation options. Run `grok update`.");
        if (text.contains("rate limit") || text.contains("quota") || text.contains("usage limit"))
            return new ProviderException("Grok Build reached an account usage limit. Retry when your allowance is available.");
        return new ProviderException("Grok Build could not complete the request (exit " + result.exitCode()
                + "). Check `grok models`, your connection and the selected model.");
    }

    @Override public String check(CancellationToken token) throws ProviderException {
        Path exe = CommandLocator.resolve(command, "grok", searchDirs), work = TempDirs.create("justbuild-grok");
        try {
            var env = isolatedContext(work, authFile);
            var help = ProcessRunner.run(List.of(exe.toString(), "--help"), "", work.resolve("work"), Duration.ofSeconds(15), token, null, env);
            for (String option : List.of("--prompt-file", "--json-schema", "--disallowed-tools", "--deny", "--no-subagents", "--verbatim"))
                if (help.exitCode() != 0 || !help.stdout().contains(option))
                    throw new ProviderException("Grok Build is too old for JustBuild. Run `grok update`.");
            modelList(exe, work, env, token);
            return exe + ", logged in";
        } finally { TempDirs.delete(work); }
    }
    @Override public List<String> models(CancellationToken token) throws ProviderException {
        Path exe = CommandLocator.resolve(command, "grok", searchDirs), work = TempDirs.create("justbuild-grok");
        try { return modelList(exe, work, isolatedContext(work, authFile), token); }
        finally { TempDirs.delete(work); }
    }
    private static List<String> modelList(Path exe, Path work, Map<String, String> env, CancellationToken token) throws ProviderException {
        var result = ProcessRunner.run(List.of(exe.toString(), "models"), "", work.resolve("work"), Duration.ofSeconds(30), token, null, env);
        if (result.exitCode() != 0) throw failure(result);
        return parseModels(result.stdout());
    }
    static List<String> parseModels(String text) throws ProviderException {
        if (text.toLowerCase(java.util.Locale.ROOT).contains("not authenticated") || !text.contains("You are logged in"))
            throw new ProviderException("Grok Build is not logged in. Run `grok login` on this computer, then retry.");
        var names = new java.util.LinkedHashSet<String>();
        for (String line : text.lines().toList()) {
            String value = line.strip();
            if (!(value.startsWith("* ") || value.startsWith("- "))) continue;
            value = value.substring(2).replace(" (default)", "").strip();
            if (value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}")) names.add(value);
            if (names.size() >= 200) break;
        }
        if (names.isEmpty()) throw new ProviderException("Grok Build listed no usable models. Check `grok models` and update the CLI.");
        return List.copyOf(names);
    }

    private static Path authFile() {
        String override = System.getenv("GROK_AUTH_PATH");
        if (override != null && !override.isBlank()) return Path.of(override);
        String home = System.getenv("GROK_HOME");
        return (home == null || home.isBlank() ? Path.of(System.getProperty("user.home"), ".grok") : Path.of(home)).resolve("auth.json");
    }

    /** GROK_AUTH_PATH lets CLI refresh/logout use the original login; no credentials are copied or read by the mod. */
    static Map<String, String> isolatedContext(Path work, Path auth) throws ProviderException {
        try {
            Path home = Files.createDirectories(work.resolve("home")), grok = Files.createDirectories(work.resolve("grok"));
            Files.createDirectories(work.resolve("work"));
            Path config = grok.resolve("config.toml"); Files.writeString(config, CONFIG);
            var env = new HashMap<String, String>();
            // A game launched from an agent shell must not inherit that agent's context or log destinations.
            for (String key : List.of("GROK_AGENT", "GROK_AGENT_ID", "GROK_AGENT_SECRET", "GROK_WORKSPACE_HOME",
                    "GROK_WORKSPACE_BUNDLED_SKILLS_DIR", "GROK_WORKSPACE_BUNDLED_SKILLS_ALLOWLIST",
                    "GROK_SNAP_EOF_ENV_VARS_B64", "GROK_LOG_FILE", "GROK_DEBUG_LOG", "GROK_HOOKS_LOG",
                    "GROK_LEADER_SOCKET", "GROK_LEADER_LOG")) env.put(key, null);
            env.put("HOME", home.toString()); env.put("USERPROFILE", home.toString());
            env.put("GROK_HOME", grok.toString()); env.put("GROK_AUTH_PATH", auth.toAbsolutePath().normalize().toString());
            env.put("GROK_CONFIG_PATH", config.toString()); env.put("GROK_CONFIG", "");
            env.put("GROK_DISABLE_AUTOUPDATER", "1"); env.put("GROK_LOGIN_ENV", "0");
            env.put("GROK_MEMORY", "0"); env.put("GROK_WORKFLOWS", "0"); env.put("GROK_SUBAGENTS", "0");
            env.put("GROK_MANAGED_MCPS_ENABLED", "0"); env.put("GROK_MANAGED_MCP_GATEWAY_TOOLS_ENABLED", "0");
            env.put("GROK_AGENT_DASHBOARD", "0");
            for (String vendor : List.of("CURSOR", "CLAUDE", "CODEX"))
                for (String surface : List.of("RULES", "SKILLS", "AGENTS", "MCPS", "HOOKS", "SESSIONS"))
                    env.put("GROK_" + vendor + "_" + surface + "_ENABLED", "0");
            return env;
        } catch (IOException e) { throw new ProviderException("Could not prepare an isolated context for Grok Build."); }
    }
}
