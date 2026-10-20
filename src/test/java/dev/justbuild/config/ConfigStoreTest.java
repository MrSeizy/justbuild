package dev.justbuild.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest extends CredentialVaultTest {
    @TempDir
    Path dir;

    @Test
    void missingFileIsCreatedWithStarterProfiles() throws Exception {
        Path file = dir.resolve("config/justbuild.json");
        JustBuildConfig config = new ConfigStore(file).load();
        assertTrue(Files.exists(file));
        assertEquals(List.of("Claude", "Codex", "OpenAI-compatible", "Grok Build"), config.profileNames());
        assertEquals("Claude", config.activeProfile);
        assertEquals("claude", config.active().provider);
        assertEquals("~/.local/bin/claude", config.claude.command);
        assertEquals("/opt/homebrew/bin/codex", config.codex.command);
        JustBuildConfig.ModelProfile openai = config.profile("openai-compatible").orElseThrow();
        assertEquals("https://api.openai.com/v1", openai.baseUrl);
        assertEquals("", openai.apiKey);
        assertNull(config.active().baseUrl);
        assertNull(openai.reasoningEffort, "the endpoint's own default until a profile sets one");
        assertEquals(250000, config.maxBlocks);
        assertEquals(600, config.timeoutSeconds);
        assertEquals("detailed", config.detail);
        assertEquals("auto", config.style);
        assertTrue(config.creativeDirection);
        assertFalse(config.reviewAiResponses);
    }

    @Test
    void handEditedValuesAreNormalized() throws Exception {
        Path file = dir.resolve("justbuild.json");
        Files.writeString(file, """
                {"claude": {"command": "/usr/local/bin/claude"}, "codex": {}, "blocksPerTick": 0, "maxQuestions": 0}
                """);
        JustBuildConfig c = new ConfigStore(file).load();
        assertEquals("/usr/local/bin/claude", c.claude.command);
        assertEquals("/opt/homebrew/bin/codex", c.codex.command);
        assertEquals(2000, c.blocksPerTick);
        assertEquals(0, c.maxQuestions);
        assertEquals(List.of("Claude", "Codex", "OpenAI-compatible", "Grok Build"), c.profiles.stream().map(p -> p.name).toList());
    }

    /** Previews, quality checks, and relayed requests size themselves from maxBlocks, up to one million. */
    @Test
    void maxBlocksIsCappedAtTheLargestSupportedBuild() throws Exception {
        Path file = dir.resolve("justbuild.json");
        Files.writeString(file, "{\"maxBlocks\": 5000000}");
        assertEquals(JustBuildConfig.MAX_BLOCKS_LIMIT, new ConfigStore(file).load().maxBlocks);
        Files.writeString(file, "{\"maxBlocks\": 250000}");
        assertEquals(250_000, new ConfigStore(file).load().maxBlocks);
    }

    @Test
    void unknownValuesFallBack() throws Exception {
        Path file = dir.resolve("justbuild.json");
        Files.writeString(file, "{\"provider\": \"gemini\", \"detail\": \"epic\", \"style\": \"baroque\"}");
        JustBuildConfig c = new ConfigStore(file).load();
        assertEquals("Claude", c.activeProfile);
        assertEquals("detailed", c.detail);
        assertEquals("auto", c.style);
        assertFalse(c.reviewAiResponses, "existing files keep automatic sending until the player opts in");
        c.reviewAiResponses = true;
        new ConfigStore(file).save(c);
        assertTrue(new ConfigStore(file).load().reviewAiResponses);
    }

    @Test
    void profilesAreNormalized() throws Exception {
        Path file = dir.resolve("justbuild.json");
        Files.writeString(file, """
                {"profiles": [
                   {"name": "  Fast  ", "provider": "codex", "model": " gpt-6-luna "},
                   null,
                   {"name": "fast", "provider": "gemini"},
                   {"name": "", "provider": "openai", "baseUrl": " ", "apiKey": null, "reasoningEffort": " NONE "},
                   {"name": "Local", "provider": "claude", "baseUrl": "http://x", "apiKey": "leak", "reasoningEffort": "low"},
                   {"name": "Odd", "provider": "openai", "reasoningEffort": "extreme"}
                 ],
                 "activeProfile": "LOCAL"}
                """);
        JustBuildConfig c = new ConfigStore(file).load();
        assertEquals(List.of("Fast", "fast 2", "Profile 3", "Local", "Odd"), c.profileNames());
        assertEquals("gpt-6-luna", c.profile("fast").orElseThrow().model);
        assertEquals("claude", c.profile("fast 2").orElseThrow().provider);
        JustBuildConfig.ModelProfile openai = c.profile("Profile 3").orElseThrow();
        assertEquals("https://api.openai.com/v1", openai.baseUrl);
        assertEquals("", openai.apiKey);
        assertNull(c.profile("Local").orElseThrow().apiKey, "only OpenAI-compatible profiles keep a key");
        assertEquals("none", openai.reasoningEffort);
        assertNull(c.profile("Local").orElseThrow().reasoningEffort, "only OpenAI-compatible profiles send one");
        assertNull(c.profile("Odd").orElseThrow().reasoningEffort, "an unknown effort falls back to the default");
        assertEquals("Local", c.activeProfile);
    }

    @Test
    void unknownActiveProfileAndEmptyListFallBack() throws Exception {
        Path file = dir.resolve("justbuild.json");
        Files.writeString(file, "{\"profiles\": [{\"name\": \"Only\", \"provider\": \"codex\"}], \"activeProfile\": \"Gone\"}");
        assertEquals("Only", new ConfigStore(file).load().activeProfile);
        Files.writeString(file, "{\"profiles\": [], \"activeProfile\": \"Gone\"}");
        JustBuildConfig c = new ConfigStore(file).load();
        assertEquals(4, c.profiles.size());
        assertEquals("Claude", c.activeProfile);
    }

    @Test
    void longNamesAndTooManyProfilesAreCut() throws Exception {
        JustBuildConfig c = new JustBuildConfig();
        c.profiles = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            c.profiles.add(new JustBuildConfig.ModelProfile("A very long profile name that keeps going " + i, "claude", ""));
        }
        c.normalize();
        assertEquals(JustBuildConfig.MAX_PROFILES, c.profiles.size());
        c.profiles.forEach(p -> assertTrue(p.name.length() <= JustBuildConfig.MAX_NAME, p.name));
        assertEquals(c.profiles.size(), c.profileNames().stream().map(String::toLowerCase).distinct().count());
    }

    @Test
    void playerAiSettingsDefaultAndFallBack() throws Exception {
        Path file = dir.resolve("justbuild.json");
        JustBuildConfig c = new ConfigStore(file).load();
        assertEquals("prefer", c.playerAi);
        assertTrue(c.useMyAi);
        Files.writeString(file, "{\"playerAi\": \" REQUIRE \", \"useMyAi\": false}");
        c = new ConfigStore(file).load();
        assertEquals("require", c.playerAi);
        assertFalse(c.useMyAi);
        Files.writeString(file, "{\"playerAi\": \"sometimes\"}");
        assertEquals("prefer", new ConfigStore(file).load().playerAi);
        assertEquals(List.of(), new ConfigStore(file).load().myAiServers, "no server gets your AI by default");
        Files.writeString(file, "{\"myAiServers\": [\" Play.Example.com \", null, \"\", \"play.example.com\"]}");
        assertEquals(List.of("play.example.com"), new ConfigStore(file).load().myAiServers);
    }

    @Test
    void invalidJsonIsReported() throws Exception {
        Path file = dir.resolve("justbuild.json");
        Files.writeString(file, "{ nope");
        var e = assertThrows(ConfigException.class, () -> new ConfigStore(file).load());
        assertTrue(e.getMessage().contains("justbuild.json"));
    }

    @Test
    void modelFollowsTheActiveProfileAndRoundTrips() throws Exception {
        Path file = dir.resolve("justbuild.json");
        ConfigStore store = new ConfigStore(file);
        JustBuildConfig c = store.load();
        assertTrue(c.activate("codex"));
        assertFalse(c.activate("nobody"));
        c.setModel("gpt-6-sol");
        store.save(c);
        JustBuildConfig loaded = store.load();
        assertEquals("Codex", loaded.activeProfile);
        assertEquals("gpt-6-sol", loaded.model());
        assertEquals("", loaded.profile("Claude").orElseThrow().model);
    }

    @Test
    void copiesAreIndependent() {
        JustBuildConfig.ModelProfile p = new JustBuildConfig.ModelProfile("Router", "openai", "m");
        p.reasoningEffort = "low";
        JustBuildConfig.ModelProfile copy = p.copy();
        copy.model = "other";
        assertEquals("m", p.model);
        assertEquals("https://api.openai.com/v1", copy.baseUrl);
        assertEquals("low", copy.reasoningEffort);
    }

    @Test
    void theSavedFileIsReadableOnlyByItsOwner() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews()
                .contains("posix"));
        java.nio.file.Path file = java.nio.file.Files.createTempDirectory("justbuild-config").resolve("justbuild.json");
        new ConfigStore(file).save(JustBuildConfig.defaults());
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                java.nio.file.Files.getPosixFilePermissions(file), "configuration stays private even with encrypted keys");
    }
}
