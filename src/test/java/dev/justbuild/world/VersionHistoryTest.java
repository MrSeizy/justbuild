package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionHistoryTest {
    @TempDir Path dir;
    @BeforeAll static void bootstrap() { McBootstrap.init(); }

    private static BuildRecord.Version edit(BuildRecord record) {
        record.addVersion(new BuildRecord.Version("edit", record.current().plan(), record.lastChanged() + 1));
        return record.current();
    }

    @Test void trimmingPreservesOfflineCollaboratorsExactTargetsAndPredecessorsAcrossRestart() throws Exception {
        BuildRecord record = BuildStoreTest.sample();
        var original = record.versions().getFirst();
        var second = record.current();
        var third = edit(record); var fourth = edit(record); var fifth = edit(record); var sixth = edit(record);
        var seventh = edit(record); var eighth = edit(record); var ninth = edit(record);
        ActionHistory history = new ActionHistory(() -> 2);
        history.push("offline-collaborator", new ActionHistory.Entry(record.id(), third.id()));
        history.push("owner", new ActionHistory.Entry(record.id(), seventh.id()));
        history.push("owner", ActionHistory.Entry.of(record));
        assertTrue(record.trimVersions(history.referencedVersions(record.id())));
        assertEquals(List.of(original, second, third, sixth, seventh, eighth, ninth), record.versions());
        assertFalse(record.versions().contains(fourth)); assertFalse(record.versions().contains(fifth));
        assertEquals(9, record.current().number(), "trimming must not renumber displayed versions");
        assertSame(ninth, record.current(), "a retained plan still has the identical source object");
        BuildStore store = new BuildStore(dir.resolve("builds")); store.save(record);
        Path historyFile = dir.resolve("history.json"); history.save(historyFile);
        BuildRecord back = store.loadAll().getFirst();
        ActionHistory reloaded = ActionHistory.load(historyFile, () -> 2);
        assertEquals(record.versions(), back.versions());
        assertEquals(history.referencedVersions(record.id()), reloaded.referencedVersions(back.id()));
        assertTrue(reloaded.peek("owner").orElseThrow().matches(back));
        // Undo ninth, then the intervening eighth is not in anyone's undo stack, so nothing pretends to undo it.
        back.removeLastVersion();
        assertEquals(eighth.id(), back.current().id());
        assertFalse(reloaded.peek("owner").orElseThrow().matches(back));
        // A later branch returning to the collaborator's version still has exactly its own predecessor.
        while (!back.current().id().equals(third.id())) back.removeLastVersion();
        assertTrue(reloaded.peek("offline-collaborator").orElseThrow().matches(back));
        back.removeLastVersion();
        assertEquals(second.id(), back.current().id());
    }

    @Test void undoThenEditAtSameCountCannotReuseOldActorOrConfirmationIdentity() throws Exception {
        BuildRecord record = BuildStoreTest.sample();
        ActionHistory.Entry old = ActionHistory.Entry.of(record);
        int number = record.current().number();
        record.removeLastVersion();
        edit(record);
        assertEquals(number, record.current().number());
        assertFalse(old.matches(record));
        assertNotEquals(old.versionId(), record.current().id());
        BuildStore store = new BuildStore(dir.resolve("builds")); store.save(record);
        assertFalse(old.matches(store.loadAll().getFirst()));
    }

    @Test void cappedHistoryAllowsTrimmingWithoutDroppingOtherActorsSteps() {
        BuildRecord record = BuildStoreTest.sample();
        ActionHistory history = new ActionHistory(() -> 1);
        history.push("friend", ActionHistory.Entry.of(record));
        var friendVersion = record.current();
        for (int i = 0; i < 30; i++) {
            edit(record); history.push("owner", ActionHistory.Entry.of(record));
            record.trimVersions(history.referencedVersions(record.id()));
        }
        assertEquals(4, record.versions().size(), "original, friend's version, newest predecessor and newest version");
        assertTrue(record.versions().contains(friendVersion));
        assertEquals(32, record.current().number());
        assertEquals(1, history.size("owner"));
    }

    @Test void adoptedOriginalAndCheckpointsSurviveAndMissingPredecessorsFailClosed() {
        BuildRecord record = BuildStoreTest.sample(); record.adopted(true);
        record.addCheckpoint(new BuildRecord.Checkpoint("original", record.versions().getFirst().plan(), 1));
        for (int i = 0; i < 12; i++) edit(record);
        record.trimVersions(Set.of());
        assertEquals(3, record.versions().size());
        assertTrue(record.canUndoCurrent());
        record.removeLastVersion();
        assertFalse(record.canUndoCurrent(), "an original retained across a gap must not become the undo predecessor");
        record.removeLastVersion();
        assertFalse(record.canUndoCurrent(), "adoption never gains a destructive root undo");
        assertEquals(1, record.checkpoints().size());
    }

    @Test void exactUndoStatesKeepVersionIdentityAndPredecessor() {
        BuildRecord record = BuildStoreTest.sample();
        var proposed = new BuildRecord.Version("repair", record.current().plan(), 3000);
        var actual = Map.of(BlockPos.ZERO, Blocks.DIAMOND_BLOCK.defaultBlockState());
        // This is also the partial-edit/shutdown path: live undo states must not create another identity.
        var applied = proposed.withUndoStates(actual);
        assertEquals(proposed.id(), applied.id());
        String previous = record.current().id(); record.addVersion(applied);
        assertEquals(proposed.id(), record.current().id()); assertEquals(previous, record.current().predecessorId());
        assertEquals(actual, record.current().undoStates());
        var held = ActionHistory.Entry.of(record);
        record.trimVersions(Set.of());
        assertTrue(held.matches(record), "a cancelled/paused undo retains the same target");
    }
}
