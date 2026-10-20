package dev.justbuild.provider;

import dev.justbuild.config.JustBuildConfig;

public final class Providers {
    private Providers() {
    }

    /** A backend for one profile; it keeps its own copies of the settings it needs. */
    public static BuildProvider create(JustBuildConfig config, JustBuildConfig.ModelProfile profile) {
        return switch (profile.provider) {
            case "codex" -> new CodexCliProvider(config.codex.command);
            case "grok" -> new GrokCliProvider(config.grok.command);
            case "openai" -> new OpenAiCompatibleProvider(profile);
            default -> new ClaudeCliProvider(config.claude.command);
        };
    }

    public static String displayName(String provider) {
        return switch (provider) {
            case "codex" -> "Codex CLI";
            case "grok" -> "Grok Build CLI";
            case "openai" -> "OpenAI-compatible";
            default -> "Claude CLI";
        };
    }
}
