package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.config.JustBuildConfig;
import org.junit.jupiter.api.Test;

class ProvidersTest {
    @Test
    void eachProfileGetsItsBackend() {
        JustBuildConfig config = JustBuildConfig.defaults();
        assertEquals("claude", Providers.create(config, config.profile("Claude").orElseThrow()).id());
        assertEquals("codex", Providers.create(config, config.profile("Codex").orElseThrow()).id());
        assertEquals("openai", Providers.create(config, config.profile("OpenAI-compatible").orElseThrow()).id());
        assertEquals("anthropic", Providers.create(config, config.profile("Anthropic-compatible").orElseThrow()).id());
        assertEquals("Anthropic-compatible", Providers.displayName("anthropic"));
        assertEquals("Codex CLI", Providers.displayName("codex"));
        assertEquals("OpenAI-compatible", Providers.displayName("openai"));
        assertEquals("Claude CLI", Providers.displayName("anything else"));
    }

    @Test
    void claudeListsItsBuiltInModelsWithoutRunningAnything() throws Exception {
        JustBuildConfig config = JustBuildConfig.defaults();
        config.claude.command = "/nonexistent/claude";
        assertEquals(ModelCatalog.CLAUDE, Providers.create(config, config.active()).models(new CancellationToken()));
    }
}
