package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModelCatalogTest {
    @Test
    void codexKeepsListedModelsInPriorityOrder() throws Exception {
        String json = """
                {"models":[
                  {"slug":"gpt-6-sol","visibility":"list","priority":3},
                  {"slug":"gpt-reserve","visibility":"hide","priority":4},
                  {"slug":"gpt-6.1-sol","visibility":"list","priority":1},
                  {"slug":"gpt-6-astra","visibility":"list","priority":2},
                  {"slug":"no-priority","visibility":"list"},
                  {"display_name":"no slug","visibility":"list","priority":0}
                ]}
                """;
        assertEquals(List.of("gpt-6.1-sol", "gpt-6-astra", "gpt-6-sol", "no-priority"), ModelCatalog.codex(json));
    }

    @Test
    void openAiListsIdsSortedAndDeduplicated() throws Exception {
        String json = """
                {"object":"list","data":[{"id":"gpt-z"},{"id":"gpt-a"},{"id":"gpt-a"},{"object":"model"}]}
                """;
        assertEquals(List.of("gpt-a", "gpt-z"), ModelCatalog.openAi(json));
    }

    @Test
    void unexpectedShapesAreErrors() {
        assertThrows(ProviderException.class, () -> ModelCatalog.codex("not json"));
        assertThrows(ProviderException.class, () -> ModelCatalog.codex("{\"other\":[]}"));
        assertThrows(ProviderException.class, () -> ModelCatalog.openAi("[1,2]"));
        var e = assertThrows(ProviderException.class, () -> ModelCatalog.openAi("{\"data\":[]}"));
        assertEquals("The endpoint listed no models.", e.getMessage());
    }

    @Test
    void claudeListStartsWithTheAliases() {
        assertEquals(List.of("fable", "opus", "sonnet"), ModelCatalog.CLAUDE.subList(0, 3));
        assertTrue(ModelCatalog.CLAUDE.contains("claude-opus-5-5"));
    }
}
