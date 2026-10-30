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

    @Test
    void apiProfilesNeedAModelAndAnHttpUrl() {
        JustBuildConfig config = JustBuildConfig.defaults();
        JustBuildConfig.ModelProfile blank = new JustBuildConfig.ModelProfile("Mine", "openai", "");
        assertTrue(Providers.problem(config, blank).contains("needs a model"));
        JustBuildConfig.ModelProfile ftp = new JustBuildConfig.ModelProfile("Mine", "openai", "m");
        ftp.baseUrl = "ftp://x";
        assertTrue(Providers.problem(config, ftp).contains("needs an http(s) base URL"));
        JustBuildConfig.ModelProfile good = new JustBuildConfig.ModelProfile("Mine", "openai", "m");
        good.baseUrl = "https://example.com/v1";
        assertNull(Providers.problem(config, good));
    }
}
