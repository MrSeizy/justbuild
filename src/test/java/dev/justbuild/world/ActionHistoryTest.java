package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActionHistoryTest {
    @TempDir Path dir;

    static ActionHistory.Entry entry(String build) {
        return new ActionHistory.Entry(build, UUID.randomUUID().toString());
    }

    @Test void stackPerActorWithCapacity() {
        ActionHistory history = new ActionHistory(() -> 3);
        var a = entry("00000001"); var b = entry("00000002"); var c = entry("00000003"); var d = entry("00000004");
        var x = entry("00000005");
        for (var entry : new ActionHistory.Entry[] {a, b, c, d}) history.push("p1", entry);
        history.push("p2", x);
        assertEquals(3, history.size("p1"));
        assertEquals(Optional.of(d), history.peek("p1"));
        assertEquals(3, history.size("p1"), "peek does not remove");
        assertEquals(Optional.empty(), history.peek("nobody"));
        assertEquals(Optional.of(d), history.pop("p1"));
        assertEquals(Optional.of(c), history.pop("p1"));
        assertEquals(Optional.of(b), history.pop("p1"));
        assertEquals(Optional.empty(), history.pop("p1"));
        assertEquals(Optional.of(x), history.pop("p2"));
    }

    @Test void savesAndLoads() throws Exception {
        Path file = dir.resolve("justbuild/history.json");
        ActionHistory history = new ActionHistory(() -> 10);
        var a = entry("00000001"); var b = entry("00000002");
        history.push("p1", a); history.push("p1", b); history.save(file);
        ActionHistory loaded = ActionHistory.load(file, () -> 10);
        assertEquals(Optional.of(b), loaded.pop("p1"));
        assertEquals(Optional.of(a), loaded.pop("p1"));
        assertEquals(0, ActionHistory.load(dir.resolve("missing.json"), () -> 10).size("p1"));
    }

    @Test void referencesIncludeAllActorsAndHonorReducedCapacityOnReloadAndAtRuntime() throws Exception {
        Path file = dir.resolve("history.json");
        AtomicInteger capacity = new AtomicInteger(3);
        ActionHistory history = new ActionHistory(capacity::get);
        var old = entry("00000001"); var recent = entry("00000001"); var friend = entry("00000001");
        history.push("owner", old); history.push("owner", recent); history.push("offline-friend", friend);
        history.save(file);
        assertEquals(Set.of(old.versionId(), recent.versionId(), friend.versionId()), history.referencedVersions("00000001"));
        var loaded = ActionHistory.load(file, () -> 1);
        assertEquals(Set.of(recent.versionId(), friend.versionId()), loaded.referencedVersions("00000001"));
        capacity.set(1);
        assertEquals(Set.of(recent.versionId(), friend.versionId()), history.referencedVersions("00000001"));
    }

    @Test void historyWithoutAFormatIsRefusedAndLeftUnchanged() throws Exception {
        Path file = dir.resolve("history.json");
        String unversioned = "{\"owner\":[\"00000001@2\",\"00000001\"],\"friend\":[\"00000001@1\"]}";
        Files.writeString(file, unversioned);
        assertThrows(java.io.IOException.class, () -> ActionHistory.load(file, () -> 10));
        assertEquals(unversioned, Files.readString(file), "load never overwrites the source");
    }

    @Test void rejectsFutureFormatsAndCountReferencesInIdentityFormat() throws Exception {
        Path file = dir.resolve("history.json");
        Files.writeString(file, "{\"format\":3,\"actors\":{}}");
        assertThrows(java.io.IOException.class, () -> ActionHistory.load(file, () -> 10));
        Files.writeString(file, "{\"format\":2,\"actors\":{\"owner\":[{\"build\":\"00000001\",\"version\":\"2\"}]}}");
        assertThrows(java.io.IOException.class, () -> ActionHistory.load(file, () -> 10));
    }
}
