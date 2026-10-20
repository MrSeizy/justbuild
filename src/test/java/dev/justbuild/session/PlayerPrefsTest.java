package dev.justbuild.session;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerPrefsTest {
    static final String PLAYER = "11111111-1111-1111-1111-111111111111";

    @TempDir
    Path dir;

    @Test
    void autoBuildIsOffUntilTurnedOnAndSurvivesSaving() throws Exception {
        PlayerPrefs prefs = PlayerPrefs.load(dir.resolve("players.json"));
        assertFalse(prefs.autoBuild(PLAYER));
        prefs.autoBuild(PLAYER, true);
        assertTrue(PlayerPrefs.load(dir.resolve("players.json")).autoBuild(PLAYER));
        prefs.autoBuild(PLAYER, false);
        assertFalse(PlayerPrefs.load(dir.resolve("players.json")).autoBuild(PLAYER));
    }

    @Test
    void anUnreadableFileMeansDefaults() throws Exception {
        Files.writeString(dir.resolve("players.json"), "{ not json");
        assertFalse(PlayerPrefs.load(dir.resolve("players.json")).autoBuild(PLAYER));
    }

    @Test
    void aPartiallyReadableFileDoesNotOptAnyoneIn() throws Exception {
        Files.writeString(dir.resolve("players.json"), "{\"autoBuild\":[\"" + PLAYER + "\",{}]}");
        assertFalse(PlayerPrefs.load(dir.resolve("players.json")).autoBuild(PLAYER));
    }

    @Test
    void failedSaveDoesNotChangeConsentInMemory() throws Exception {
        Path file = dir.resolve("players.json");
        PlayerPrefs prefs = PlayerPrefs.load(file);
        Files.createDirectory(file.resolveSibling("players.json.tmp"));
        assertThrows(java.io.IOException.class, () -> prefs.autoBuild(PLAYER, true));
        assertFalse(prefs.autoBuild(PLAYER));
        Files.delete(file.resolveSibling("players.json.tmp"));
        prefs.autoBuild(PLAYER, true);
        Files.createDirectory(file.resolveSibling("players.json.tmp"));
        assertThrows(java.io.IOException.class, () -> prefs.autoBuild(PLAYER, false));
        assertTrue(prefs.autoBuild(PLAYER));
        assertTrue(PlayerPrefs.load(file).autoBuild(PLAYER));
    }
}
