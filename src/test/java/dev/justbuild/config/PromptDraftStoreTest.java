package dev.justbuild.config;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptDraftStoreTest {
    @TempDir Path dir;
    @Test void draftsKeepExactTextAndContextAcrossRestartAndSendingRemovesOnlyThatDraft() throws Exception {
        Path file = dir.resolve("drafts.json");
        LinkedHashMap<String, String> drafts = new LinkedHashMap<>();
        drafts.put("player\nworld-a\nnew\n", "  A cabin\nwith a café 🍂\n");
        drafts.put("player\nworld-b\nnew\n", "A different world");
        drafts.put("player\nworld-a\nbuild-id\nquestion", "My unsent answer");
        new PromptDraftStore(file).save(drafts);
        var restored = new PromptDraftStore(file).load();
        assertEquals(drafts, restored);
        assertEquals(List.copyOf(drafts.keySet()), List.copyOf(restored.keySet()));
        restored.remove("player\nworld-a\nnew\n");
        new PromptDraftStore(file).save(restored);
        assertEquals(restored, new PromptDraftStore(file).load());
        assertEquals(2, restored.size());
    }
    @Test void maximumDraftsRoundTripAndOversizeWritesPreserveTheLastGoodFile() throws Exception {
        Path file = dir.resolve("drafts.json"); PromptDraftStore store = new PromptDraftStore(file);
        Map<String, String> drafts = new LinkedHashMap<>();
        for (int i = 0; i < PromptDraftStore.LIMIT; i++) drafts.put("world-" + i, "界".repeat(4096));
        store.save(drafts);
        assertEquals(drafts, new PromptDraftStore(file).load());
        byte[] original = Files.readAllBytes(file);
        drafts.put("extra", "one too many");
        assertThrows(IOException.class, () -> store.save(drafts));
        assertThrows(IOException.class, () -> store.save(Map.of("a", "a".repeat(4097))));
        assertArrayEquals(original, Files.readAllBytes(file));
    }
    @Test void unreadableAndUnknownVersionsAreNeverSilentlyOverwritten() throws Exception {
        Path file = dir.resolve("drafts.json"); PromptDraftStore store = new PromptDraftStore(file);
        for (String json : List.of("broken", "{\"format\":2,\"drafts\":[]}",
                "{\"format\":1,\"drafts\":[{\"key\":\"a\",\"text\":\"first\"},{\"key\":\"a\",\"text\":\"second\"}]}")) {
            Files.writeString(file, json);
            assertThrows(IOException.class, store::load);
            assertThrows(IOException.class, () -> store.save(Map.of("new", "draft")));
            assertEquals(json, Files.readString(file));
        }
    }
}
