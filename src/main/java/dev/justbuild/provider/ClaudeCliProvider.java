package dev.justbuild.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Uses the logged-in `claude` CLI (Claude subscription) headless, with every tool disabled. */
public final class ClaudeCliProvider implements BuildProvider {
    private final String command;
    private final List<Path> searchDirs;

    /** {@code command} is the configured path (claude.command); blank or missing means search the usual places. */
    public ClaudeCliProvider(String command) {
        this(command, CommandLocator.defaultSearchDirs());
    }

    ClaudeCliProvider(String command, List<Path> searchDirs) {
        this.command = command;
        this.searchDirs = searchDirs;
    }

    @Override
    public String id() {
        return "claude";
    }

    @Override
    public String displayName() {
        return "Claude CLI";
    }

    /** No --bare: it disables OAuth/keychain auth, which is the subscription login this backend exists for. */
    static List<String> command(Path exe, PlanRequest request) {
        List<String> cmd = new ArrayList<>(List.of(exe.toString(), "-p", "--output-format", "stream-json", "--verbose",
                "--include-partial-messages",
                "--json-schema", request.schemaJson(), "--system-prompt", request.systemPrompt(),
                "--tools", "", "--restricted", "--safe-mode", "--disable-slash-commands",
                "--strict-mcp-config", "--no-session-persistence"));
        if (request.model() != null && !request.model().isBlank()) {
            cmd.add("--model");
            cmd.add(request.model().trim());
        }
        return cmd;
    }

    /** The CLI cannot list models, so this is a built-in list; nothing runs. */
    @Override
    public List<String> models(CancellationToken token) {
        return ModelCatalog.CLAUDE;
    }

    /** Finds the claude binary; explains that the Claude desktop app alone is not enough. */
    private Path locate() throws ProviderException {
        try {
            return CommandLocator.resolve(command, "claude", searchDirs);
        } catch (ProviderException e) {
            String home = System.getProperty("user.home");
            boolean desktopApp = java.nio.file.Files.isDirectory(Path.of("/Applications/Claude.app"))
                    || java.nio.file.Files.isDirectory(Path.of(home, "Applications/Claude.app"));
            if (!desktopApp) {
                throw e;
            }
            throw new ProviderException("The Claude desktop app doesn't include a Claude CLI the mod can run. Install "
                    + "Claude Code with `brew install --cask claude-code`, then run `claude auth login`. ("
                    + e.getMessage() + ")");
        }
    }

    @Override
    public String complete(PlanRequest request, CancellationToken token, ProgressListener progress)
            throws ProviderException {
        CliModel.require(request.model());
        Path exe = locate();
        Path work = TempDirs.create("justbuild-claude");
        try {
            ClaudeStream stream = new ClaudeStream(progress);
            List<String> args = command(exe, request);
            var images = request.images();
            String input = request.userContent();
            if (!images.isEmpty()) {
                args.addAll(List.of("--input-format", "stream-json"));
                input = imageMessage(input, images);
            }
            ProcessResult r = ProcessRunner.runWithIdleTimeout(args, input, work, request.timeout(),
                    token, stream::accept, isolatedContext(), progress::activity);
            String result = stream.resultLine();
            return ClaudeOutput.extract(new ProcessResult(r.exitCode(), result != null ? result : r.stdout(), r.stderr()));
        } finally {
            TempDirs.delete(work);
        }
    }

    static String imageMessage(String text, List<byte[]> images) {
        var content = new com.google.gson.JsonArray();
        JsonObject words = new JsonObject(); words.addProperty("type", "text"); words.addProperty("text", text); content.add(words);
        for (byte[] png : images) {
            JsonObject source = new JsonObject(); source.addProperty("type", "base64"); source.addProperty("media_type", "image/png");
            source.addProperty("data", java.util.Base64.getEncoder().encodeToString(png));
            JsonObject image = new JsonObject(); image.addProperty("type", "image"); image.add("source", source); content.add(image);
        }
        JsonObject message = new JsonObject(); message.addProperty("role", "user"); message.add("content", content);
        JsonObject event = new JsonObject(); event.addProperty("type", "user"); event.add("message", message);
        return event + "\n";
    }

    @Override
    public String check(CancellationToken token) throws ProviderException {
        Path exe = locate();
        Path work = TempDirs.create("justbuild-claude");
        try {
            ProcessResult r = ProcessRunner.run(List.of(exe.toString(), "auth", "status"), "", work,
                    Duration.ofSeconds(30), token);
            JsonObject status;
            try {
                JsonElement e = JsonParser.parseString(r.stdout().trim());
                status = e.isJsonObject() ? e.getAsJsonObject() : null;
            } catch (JsonParseException e) {
                status = null;
            }
            if (status == null || !status.has("loggedIn")) {
                throw new ProviderException(exe + " auth status failed: " + ProcessRunner.tail(r.stderr() + r.stdout(), 2));
            }
            if (!status.get("loggedIn").getAsBoolean()) {
                throw new ProviderException("not logged in. Run `claude auth login` in a terminal.");
            }
            ProcessResult help = ProcessRunner.run(List.of(exe.toString(), "--help"), "", work, Duration.ofSeconds(30),
                    token);
            if (!help.stdout().contains("--restricted") || !help.stdout().contains("--safe-mode")) {
                throw new ProviderException(exe + " is too old for JustBuild (requires --restricted and --safe-mode). Update it "
                        + "with `claude update` or `brew upgrade --cask claude-code`.");
            }
            String method = status.has("authMethod") ? status.get("authMethod").getAsString() : "unknown";
            return exe + ", logged in (" + method + ")";
        } finally {
            TempDirs.delete(work);
        }
    }

    /** Keep subscription authentication, but never expand personal instructions, memory, skills, or @file mentions. */
    static Map<String, String> isolatedContext() {
        return Map.of("CLAUDE_CODE_DISABLE_CLAUDE_MDS", "1", "CLAUDE_CODE_DISABLE_AUTO_MEMORY", "1",
                "CLAUDE_CODE_DISABLE_ATTACHMENTS", "1");
    }
}
