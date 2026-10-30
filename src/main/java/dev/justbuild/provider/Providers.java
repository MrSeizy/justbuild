package dev.justbuild.provider;

import dev.justbuild.config.JustBuildConfig;
import java.net.URI;

public final class Providers {
    private Providers() {
    }

    /** A backend for one profile; it keeps its own copies of the settings it needs. */
    public static BuildProvider create(JustBuildConfig config, JustBuildConfig.ModelProfile profile) {
        return switch (profile.provider) {
            case "codex" -> new CodexCliProvider(config.codex.command);
            case "grok" -> new GrokCliProvider(config.grok.command);
            case "openai" -> new OpenAiCompatibleProvider(profile);
            case "anthropic" -> new AnthropicCompatibleProvider(profile);
            default -> new ClaudeCliProvider(config.claude.command);
        };
    }

    /** Why this profile cannot plan builds right now, or null if it can. */
    public static String problem(JustBuildConfig config, JustBuildConfig.ModelProfile p) {
        try {
            switch (p.provider) {
                case "codex" -> CommandLocator.resolve(config.codex.command, "codex", CommandLocator.defaultSearchDirs());
                case "grok" -> CommandLocator.resolve(config.grok.command, "grok", CommandLocator.defaultSearchDirs());
                case "openai", "anthropic" -> {
                    if (p.model.isBlank()) {
                        return "“" + p.name + "” needs a model.";
                    }
                    String scheme = URI.create(p.baseUrl).getScheme();
                    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                        return "“" + p.name + "” needs an http(s) base URL.";
                    }
                }
                default -> CommandLocator.resolve(config.claude.command, "claude", CommandLocator.defaultSearchDirs());
            }
            return null;
        } catch (ProviderException e) {
            return e.getMessage();
        } catch (IllegalArgumentException e) {
            return "“" + p.name + "” has an invalid base URL.";
        }
    }

    public static String displayName(String provider) {
        return switch (provider) {
            case "codex" -> "Codex CLI";
            case "grok" -> "Grok Build CLI";
            case "openai" -> "OpenAI-compatible";
            case "anthropic" -> "Anthropic-compatible";
            default -> "Claude CLI";
        };
    }
}
