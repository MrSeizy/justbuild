package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.Operation;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildStoreTest {
    @TempDir
    Path dir;

    @BeforeAll
    static void bootstrap() {
        McBootstrap.init();
    }

    static BuildRecord sample() {
        BuildPlan v1 = new BuildPlan("Hut", "A hut.", List.of(Operation.fill("oak_planks", new Cell(0, 0, 0), new Cell(2, 2, 2), true)));
        BuildPlan v2 = new BuildPlan("Hut", "A taller hut.", List.of(Operation.fill("oak_planks", new Cell(0, 0, 0), new Cell(2, 4, 2), true)));
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> baseline = new HashMap<>();
        baseline.put(new BlockPos(5, 64, -3), Blocks.GRASS_BLOCK.defaultBlockState());
        baseline.put(new BlockPos(6, 64, -3), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
        return new BuildRecord("0a1b2c3d", "player-uuid", Level.NETHER, new BlockPos(5, 64, -3), Direction.WEST,
                List.of(new BuildRecord.Version("a hut", v1, 1000L), new BuildRecord.Version("make it taller", v2, 2000L)),
                baseline);
    }

    @Test
    void roundTripsThroughGzipJson() throws Exception {
        BuildStore store = new BuildStore(dir.resolve("builds"));
        BuildRecord record = sample();
        store.save(record);
        assertTrue(Files.exists(dir.resolve("builds/0a1b2c3d.json.gz")));
        List<BuildRecord> loaded = store.loadAll();
        assertEquals(1, loaded.size());
        BuildRecord back = loaded.get(0);
        assertEquals("0a1b2c3d", back.id());
        assertEquals("player-uuid", back.owner());
        assertEquals(Level.NETHER, back.dimension());
        assertEquals(new BlockPos(5, 64, -3), back.origin());
        assertEquals(Direction.WEST, back.facing());
        assertEquals(record.versions(), back.versions());
        assertEquals(record.baseline(), back.baseline());
    }

    @Test
    void skipsUnparseableBaselineStatesAndBadFiles() throws Exception {
        Path builds = dir.resolve("builds");
        BuildStore store = new BuildStore(builds);
        store.save(sample());
        Path file = builds.resolve("0a1b2c3d.json.gz");
        String json = BuildStore.readText(file).replace("minecraft:grass_block", "gone:block");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        Files.writeString(builds.resolve("broken.json.gz"), "not gzip");
        List<BuildRecord> loaded = store.loadAll();
        assertEquals(1, loaded.size());
        assertEquals(1, loaded.get(0).baseline().size());
        assertEquals(List.of("broken.json.gz"), store.loadProblems());
        assertEquals("not gzip", Files.readString(builds.resolve("broken.json.gz")), "keep the broken record for recovery");
        Files.delete(builds.resolve("broken.json.gz"));
        store.loadAll();
        assertTrue(store.loadProblems().isEmpty(), "recovery must clear the reported failures");
    }

    @Test
    void aNonDirectoryStoreAndMismatchedRecordIdentityAreReported() throws Exception {
        Path file = dir.resolve("not-a-directory"); Files.writeString(file, "original");
        BuildStore invalid = new BuildStore(file);
        assertTrue(invalid.loadAll().isEmpty());
        assertFalse(invalid.loadProblems().isEmpty());
        BuildStore store = new BuildStore(dir.resolve("builds")); store.save(sample());
        Files.move(dir.resolve("builds/0a1b2c3d.json.gz"), dir.resolve("builds/12345678.json.gz"));
        assertTrue(store.loadAll().isEmpty());
        assertEquals(List.of("12345678.json.gz"), store.loadProblems());
    }

    @Test
    void deleteRemovesTheFile() throws Exception {
        BuildStore store = new BuildStore(dir.resolve("builds"));
        store.save(sample());
        store.delete("0a1b2c3d");
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    void namesAreasAndCheckpointsSurviveSavingAndUndo() throws Exception {
        BuildRecord record = sample();
        record.rename("Lakeside House");
        BuildZone area = new BuildZone(Level.NETHER, 1, -7, 12, 9, 64);
        record.zone(area);
        var checkpoint = new BuildRecord.Checkpoint("Before upstairs", record.current().plan(), 2100);
        record.addCheckpoint(checkpoint);
        record.removeLastVersion();
        BuildStore store = new BuildStore(dir.resolve("builds"));
        store.save(record);
        BuildRecord loaded = store.loadAll().getFirst();
        assertEquals("Lakeside House", loaded.title());
        assertEquals(area, loaded.zone());
        assertTrue(loaded.hasSavedZone());
        assertEquals(List.of(checkpoint), loaded.checkpoints());
        assertEquals(1, loaded.versions().size());
        assertThrows(IllegalArgumentException.class, () -> loaded.addCheckpoint(checkpoint));
        assertThrows(IllegalArgumentException.class, () -> loaded.rename("bad\nname"));
        assertThrows(IllegalArgumentException.class, () -> loaded.zone(new BuildZone(Level.OVERWORLD, 0, 0, 1, 1, 64)));
    }

    @Test
    void recordsWithoutASavedAreaAndExplicitlyClearedAreasRemainDistinct() throws Exception {
        var unsaved = BuildStore.toJson(sample());
        for (String key : List.of("areaSaved", "zone", "name", "checkpoints")) unsaved.remove(key);
        BuildRecord loaded = BuildStore.fromJson(unsaved);
        assertEquals("Hut", loaded.title());
        assertFalse(loaded.hasSavedZone());
        assertTrue(loaded.checkpoints().isEmpty());
        loaded.zone(null);
        BuildRecord cleared = BuildStore.fromJson(BuildStore.toJson(loaded));
        assertTrue(cleared.hasSavedZone());
        assertNull(cleared.zone());
    }

    /** sample() with exact undo states on its first (older) and second (current) versions. */
    static BuildRecord withUndo(int olderEntries, int currentEntries) {
        BuildRecord s = sample();
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> older = new HashMap<>(), current = new HashMap<>();
        for (int i = 0; i < olderEntries; i++) older.put(new BlockPos(i, 70, 0), Blocks.STONE.defaultBlockState());
        for (int i = 0; i < currentEntries; i++) current.put(new BlockPos(i, 71, 0), Blocks.DIRT.defaultBlockState());
        BuildRecord.Version v1 = s.versions().get(0), v2 = s.versions().get(1);
        return new BuildRecord(s.id(), s.owner(), s.dimension(), s.origin(), s.facing(),
                List.of(new BuildRecord.Version(v1.request(), v1.plan(), v1.createdAt(), older),
                        new BuildRecord.Version(v2.request(), v2.plan(), v2.createdAt(), current)), s.baseline());
    }

    /** The server thread only takes the snapshot; what is written is the build as it was when the save was queued. */
    @Test
    void aBackgroundSaveWritesTheSnapshotTakenWhenItWasQueued() throws Exception {
        BuildStore store = new BuildStore(dir.resolve("builds"));
        BuildRecord record = sample();
        record.rename("First");
        assertTrue(store.saveLater(record, e -> fail(e)));
        record.rename("Second");
        store.flush();
        assertEquals("First", store.loadAll().get(0).title());
        assertTrue(store.saveLater(record, e -> fail(e)));
        assertEquals("Second", store.loadAll().get(0).title(), "loading waits for queued saves");
        store.close();
    }

    @Test
    void aDeleteAfterAQueuedSaveLeavesNoFile() throws Exception {
        BuildStore store = new BuildStore(dir.resolve("builds"));
        BuildRecord record = sample();
        store.saveLater(record, e -> fail(e));
        store.delete(record.id());
        store.flush();
        assertFalse(Files.exists(dir.resolve("builds/" + record.id() + ".json.gz")));
        assertTrue(store.loadAll().isEmpty());
        store.close();
    }

    @Test
    void aFailedBackgroundSaveIsReported() throws Exception {
        Path blocked = dir.resolve("builds");
        Files.writeString(blocked, "a file where the folder should be");
        BuildStore store = new BuildStore(blocked);
        var failure = new java.util.concurrent.atomic.AtomicReference<java.io.IOException>();
        store.saveLater(sample(), failure::set);
        store.flush();
        assertNotNull(failure.get(), "the caller must hear that the build was not written");
        store.close();
    }

    @Test
    void closingWritesEverythingStillQueuedAndRefusesMore() throws Exception {
        BuildStore store = new BuildStore(dir.resolve("builds"));
        for (int i = 0; i < 20; i++) {
            BuildRecord record = sample();
            store.saveLater(new BuildRecord(String.format("%08x", i), record.owner(), record.dimension(), record.origin(),
                    record.facing(), record.versions(), record.baseline()), e -> fail(e));
        }
        store.close();
        try (var files = Files.list(dir.resolve("builds"))) {
            assertEquals(20, files.filter(f -> f.toString().endsWith(".json.gz")).count());
        }
        assertFalse(store.saveLater(sample(), e -> fail(e)), "a closed store accepts no more saves");
    }

    /**
     * Map.copyOf probes linearly, and BlockPos hash codes of a box are dense consecutive integers, so copying a
     * quarter-million positions took tens of seconds on the server thread.
     */
    @Test
    void aVersionWithAQuarterMillionUndoStatesIsCreatedQuickly() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> undo = new HashMap<>();
        for (int i = 0; i < 250_000; i++) undo.put(new BlockPos(i % 96, 64 + i / 9216, (i / 96) % 96), Blocks.STONE.defaultBlockState());
        BuildPlan plan = sample().current().plan();
        var version = assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                () -> new BuildRecord.Version("swap", plan, 1L, undo));
        assertEquals(undo, version.undoStates());
        assertThrows(UnsupportedOperationException.class, () -> version.undoStates().clear());
    }

    /** A record past the read cap used to save fine and then be skipped on every load, taking its protection along. */
    @Test
    void anOversizedRecordShedsOlderUndoStatesInsteadOfBecomingUnloadable() throws Exception {
        BuildRecord record = withUndo(400, 3);
        int full = BuildStore.toJson(record).toString().getBytes(StandardCharsets.UTF_8).length;
        BuildStore store = new BuildStore(dir.resolve("builds"), full - 1);
        store.save(record);
        List<BuildRecord> loaded = store.loadAll();
        assertEquals(1, loaded.size(), "the saved record must load again");
        assertEquals(record.current().undoStates(), loaded.get(0).current().undoStates());
        assertTrue(loaded.get(0).versions().get(0).undoStates().isEmpty());
    }

    @Test
    void aRecordThatCannotFitIsNotSavedOverTheLastReadableCopy() throws Exception {
        BuildRecord small = sample();
        BuildStore store = new BuildStore(dir.resolve("builds"),
                BuildStore.toJson(small).toString().getBytes(StandardCharsets.UTF_8).length + 100);
        store.save(small);
        assertThrows(java.io.IOException.class, () -> store.save(withUndo(0, 400)));
        List<BuildRecord> loaded = store.loadAll();
        assertEquals(1, loaded.size());
        assertEquals(small.versions(), loaded.get(0).versions());
    }

    @Test
    void anUnknownUndoBlockSkipsThatEntryNotTheBuild() throws Exception {
        BuildRecord record = sample();
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> undo = new HashMap<>();
        undo.put(new BlockPos(5, 64, -3), Blocks.STONE.defaultBlockState());
        undo.put(new BlockPos(6, 64, -3), Blocks.DIRT.defaultBlockState());
        BuildRecord.Version v = record.current();
        record.removeLastVersion();
        record.addVersion(new BuildRecord.Version(v.request(), v.plan(), v.createdAt(), undo));
        String json = BuildStore.toJson(record).toString().replace("minecraft:dirt", "somemod:removed_block");
        BuildRecord back = BuildStore.fromJson(com.google.gson.JsonParser.parseString(json).getAsJsonObject());
        assertEquals(Map.of(new BlockPos(5, 64, -3), Blocks.STONE.defaultBlockState()), back.current().undoStates());
    }

    @Test
    void idsAndFacingFromAFileMustBeValid() {
        for (String[] bad : new String[][] {{"id", "../../../other/justbuild/builds/1234abcd"}, {"id", "/tmp/x"},
                {"facing", "up"}, {"facing", "sideways"}}) {
            com.google.gson.JsonObject o = BuildStore.toJson(sample());
            o.addProperty(bad[0], bad[1]);
            assertThrows(java.io.IOException.class, () -> BuildStore.fromJson(o), bad[0] + "=" + bad[1]);
        }
    }


    @Test
    void otherBuildFormatsAndMissingOrRepeatedVersionIdentitiesAreRefused() {
        for (int format : new int[] {1, 4, 6}) {
            var other = BuildStore.toJson(sample()); other.addProperty("format", format);
            assertThrows(java.io.IOException.class, () -> BuildStore.fromJson(other), "format " + format);
        }
        var unversioned = BuildStore.toJson(sample()); unversioned.remove("format");
        assertThrows(java.io.IOException.class, () -> BuildStore.fromJson(unversioned));
        var missing = BuildStore.toJson(sample()); missing.getAsJsonArray("versions").get(0).getAsJsonObject().remove("id");
        assertThrows(java.io.IOException.class, () -> BuildStore.fromJson(missing));
        var repeated = BuildStore.toJson(sample());
        var versions = repeated.getAsJsonArray("versions");
        versions.get(1).getAsJsonObject().add("id", versions.get(0).getAsJsonObject().get("id"));
        assertThrows(java.io.IOException.class, () -> BuildStore.fromJson(repeated));
    }

    @Test
    void oversizedFilesAreSkippedInsteadOfFillingMemory() throws Exception {
        BuildStore store = new BuildStore(dir.resolve("builds"));
        store.save(sample());
        Path bomb = dir.resolve("builds/ffffffff.json.gz");
        try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(bomb))) {
            byte[] zeros = new byte[1 << 20];
            for (int i = 0; i < 65; i++) out.write(zeros);
        }
        assertEquals(List.of("0a1b2c3d"), store.loadAll().stream().map(BuildRecord::id).toList());
    }
}
