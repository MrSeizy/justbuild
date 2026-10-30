package dev.justbuild.config;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.config.JustBuildConfig.ModelProfile;
import dev.justbuild.config.ProfileEdits.Edit;
import dev.justbuild.config.ProfileEdits.Request;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfileEditsTest {
    static final String ROUTER = "https://openrouter.ai/api/v1";

    static List<ModelProfile> current() {
        JustBuildConfig c = new JustBuildConfig();
        c.normalize();
        c.profile("OpenAI-compatible").orElseThrow().apiKey = "sk-old";
        // Exercise edits to the four profiles shipped in 0.1.0.
        return c.profiles.subList(0, 4);
    }

    static Edit unchanged(ModelProfile p) {
        return new Edit(p.name, p.name, p.provider, p.model, p.baseUrl, null);
    }

    static List<Edit> unchangedAll() {
        List<Edit> edits = new ArrayList<>();
        current().forEach(p -> edits.add(unchanged(p)));
        return edits;
    }

    @Test
    void renamedFollowsAProfileThroughItsEdit() {
        var request = new ProfileEdits.Request(List.of(
                new ProfileEdits.Edit("Claude", "Claude fast", "claude", "", null, null),
                new ProfileEdits.Edit(null, "New", "codex", "", null, null)), "Claude fast");
        assertEquals("Claude fast", ProfileEdits.renamed(request, "claude"));
        assertEquals("", ProfileEdits.renamed(request, "Codex"), "a removed profile clears the reviewer");
        assertEquals("", ProfileEdits.renamed(request, ""));
        assertEquals("", ProfileEdits.renamed(request, null));
    }

    @Test
    void blankKeyBoxKeepsTheSavedKeyThroughARename() throws Exception {
        List<Edit> edits = unchangedAll();
        edits.set(2, new Edit("OpenAI-compatible", "Work", "openai", "gpt-x", " https://API.openai.com/v1/ ", null));
        List<ModelProfile> out = ProfileEdits.apply(current(), new Request(edits, "Work"));
        ModelProfile work = out.get(2);
        assertEquals("Work", work.name);
        assertEquals("sk-old", work.apiKey);
        assertEquals("gpt-x", work.model);
    }

    @Test
    void aNewBaseUrlNeedsTheKeyAgain() throws Exception {
        List<Edit> edits = unchangedAll();
        edits.set(2, new Edit("OpenAI-compatible", "OpenRouter", "openai", "deepseek/deepseek-chat", ROUTER, null));
        ModelProfile router = ProfileEdits.apply(current(), new Request(edits, "OpenRouter")).get(2);
        assertEquals(ROUTER, router.baseUrl);
        assertEquals("", router.apiKey, "the saved key must not follow the profile to another host");
        edits.set(2, new Edit("OpenAI-compatible", "OpenRouter", "openai", "", ROUTER, "sk-or"));
        assertEquals("sk-or", ProfileEdits.apply(current(), new Request(edits, "OpenRouter")).get(2).apiKey);
    }

    @Test
    void aTypedKeyReplacesItAndAnEmptyOneClearsIt() throws Exception {
        List<Edit> edits = unchangedAll();
        edits.set(2, new Edit("OpenAI-compatible", "OpenAI-compatible", "openai", "", ROUTER, "  sk-new  "));
        assertEquals("sk-new", ProfileEdits.apply(current(), new Request(edits, "Claude")).get(2).apiKey);
        edits.set(2, new Edit("OpenAI-compatible", "OpenAI-compatible", "openai", "", JustBuildConfig.DEFAULT_BASE_URL, ""));
        assertEquals("", ProfileEdits.apply(current(), new Request(edits, "Claude")).get(2).apiKey);
    }

    @Test
    void newProfilesAndOtherProvidersNeverInheritAKey() throws Exception {
        List<Edit> edits = unchangedAll();
        edits.add(new Edit(null, "Ollama", "openai", "llama4", "http://localhost:11434/v1", null));
        edits.add(new Edit("Claude", "Claude copy", "openai", "", ROUTER, null));
        edits.set(2, new Edit("OpenAI-compatible", "Was OpenAI", "claude", "opus", ROUTER, null));
        List<ModelProfile> out = ProfileEdits.apply(current(), new Request(edits, "ollama"));
        assertEquals("", out.get(4).apiKey);
        assertEquals("", out.get(5).apiKey);
        assertNull(out.get(3).apiKey, "Grok uses its CLI login");
        assertNull(out.get(2).apiKey);
        assertNull(out.get(2).baseUrl);
    }

    @Test
    void removingAndReorderingWorks() throws Exception {
        List<Edit> edits = unchangedAll();
        edits.remove(0);
        Collections.reverse(edits);
        List<ModelProfile> out = ProfileEdits.apply(current(), new Request(edits, "Codex"));
        assertEquals(List.of("Grok Build", "OpenAI-compatible", "Codex"), out.stream().map(p -> p.name).toList());
        assertEquals("sk-old", out.get(1).apiKey);
    }

    @Test
    void rejectsBadEdits() {
        assertError("Keep at least one profile.", List.of(), "Claude");
        assertError("Every profile needs a name.", List.of(new Edit(null, "  ", "claude", "", null, null)), "x");
        assertError("Two profiles are called “fast”.", List.of(new Edit(null, "Fast", "claude", "", null, null),
                new Edit(null, "fast", "codex", "", null, null)), "Fast");
        assertError("Unknown provider “gemini” in “G”.", List.of(new Edit(null, "G", "gemini", "", null, null)), "G");
        assertError("“Web”: the base URL must start with http:// or https://.",
                List.of(new Edit(null, "Web", "openai", "", "ftp://example.com", null)), "Web");
        assertError("“Web”: the base URL must start with http:// or https://.",
                List.of(new Edit(null, "Web", "openai", "", "not a url", null)), "Web");
        assertError("No profile called “Gone”.", List.of(new Edit(null, "Here", "claude", "", null, null)), "Gone");
        assertError("Profile names can be at most 32 characters.",
                List.of(new Edit(null, "x".repeat(33), "claude", "", null, null)), "x");
        List<Edit> many = new ArrayList<>();
        for (int i = 0; i <= JustBuildConfig.MAX_PROFILES; i++) {
            many.add(new Edit(null, "P" + i, "claude", "", null, null));
        }
        assertError("At most 20 profiles.", many, "P0");
    }

    @Test
    void resolvesOneDraftForAModelListRequest() throws Exception {
        String saved = JustBuildConfig.DEFAULT_BASE_URL;
        ModelProfile p = ProfileEdits.resolve(current(), new Edit("OpenAI-compatible", "Draft", "openai", "", saved, null));
        assertEquals("sk-old", p.apiKey);
        assertEquals(saved, p.baseUrl);
        assertEquals("", ProfileEdits.resolve(current(),
                new Edit("OpenAI-compatible", "Draft", "openai", "", "https://attacker.example/v1", null)).apiKey);
        var e = assertThrows(ConfigException.class, () -> ProfileEdits.resolve(current(),
                new Edit(null, "Draft", "openai", "", "nope", null)));
        assertTrue(e.getMessage().contains("base URL"));
    }

    @Test
    void anthropicEditsValidateTokensAndPinSavedKeysToTheProvider() throws Exception {
        var saved = new ModelProfile("API", "anthropic", "model");
        saved.apiKey = "synthetic-anthropic-key";
        var same = new Edit("API", "Renamed", "anthropic", "new model", saved.baseUrl, null, "high", 24000);
        var roundTrip = ProfileEdits.parseEdit(ProfileEdits.toJson(same));
        assertEquals(same, roundTrip);
        var resolved = ProfileEdits.resolve(List.of(saved), roundTrip);
        assertEquals(saved.apiKey, resolved.apiKey);
        assertEquals(24000, resolved.maxTokens);
        assertNull(resolved.reasoningEffort);
        assertEquals(8192, ProfileEdits.resolve(List.of(saved),
                new Edit("API", "API", "anthropic", "model", saved.baseUrl, null)).maxTokens);
        assertEquals("", ProfileEdits.resolve(List.of(saved),
                new Edit("API", "API", "openai", "model", saved.baseUrl, null)).apiKey);
        assertEquals("", ProfileEdits.resolve(List.of(saved),
                new Edit("API", "API", "anthropic", "model", "https://other.example/v1", null)).apiKey);
        for (int tokens : List.of(0, -1, 128001)) {
            assertThrows(ConfigException.class, () -> ProfileEdits.resolve(List.of(saved),
                    new Edit("API", "API", "anthropic", "model", saved.baseUrl, null, null, tokens)));
        }
        assertThrows(ConfigException.class, () -> ProfileEdits.parseEdit("{\"maxTokens\":1.5}"));
    }

    @Test
    void reasoningEffortIsKeptForOpenAiProfilesOnly() throws Exception {
        assertEquals("none", ProfileEdits.resolve(current(),
                new Edit(null, "Fast", "openai", "m", ROUTER, null, " None ")).reasoningEffort);
        assertNull(ProfileEdits.resolve(current(), new Edit(null, "Fast", "openai", "m", ROUTER, null, "")).reasoningEffort);
        assertNull(ProfileEdits.resolve(current(), new Edit(null, "Fast", "openai", "m", ROUTER, null)).reasoningEffort);
        assertNull(ProfileEdits.resolve(current(), new Edit(null, "Cli", "codex", "", null, null, "low")).reasoningEffort);
        var e = assertThrows(ConfigException.class, () -> ProfileEdits.resolve(current(),
                new Edit(null, "Fast", "openai", "m", ROUTER, null, "extreme")));
        assertEquals("“Fast”: reasoning must be none, low, medium, or high.", e.getMessage());
    }

    @Test
    void jsonRoundTripsIncludingNulls() throws Exception {
        Request request = new Request(List.of(new Edit(null, "Ollama", "openai", "llama4", "http://localhost:11434/v1", null),
                new Edit("Claude", "Claude", "claude", "", null, ""),
                new Edit(null, "Fast", "openai", "m", ROUTER, null, "none")), "Ollama");
        assertEquals(request, ProfileEdits.parse(ProfileEdits.toJson(request)));
        Edit edit = new Edit("A", "B", "codex", "m", null, "k");
        assertEquals(edit, ProfileEdits.parseEdit(ProfileEdits.toJson(edit)));
        assertThrows(ConfigException.class, () -> ProfileEdits.parse("{nope"));
        assertThrows(ConfigException.class, () -> ProfileEdits.parse("{\"profiles\": 3}"));
    }

    private static void assertError(String message, List<Edit> edits, String active) {
        var e = assertThrows(ConfigException.class, () -> ProfileEdits.apply(current(), new Request(edits, active)));
        assertEquals(message, e.getMessage());
    }
}
