package dev.justbuild.config;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptedCredentialsTest extends CredentialVaultTest {
    private static final String SECRET = "synthetic-api-credential-for-tests";
    @TempDir Path dir;

    private JustBuildConfig config() {
        var config = JustBuildConfig.defaults();
        config.activate("OpenAI-compatible"); config.active().apiKey = SECRET; config.active().model = "test";
        return config;
    }
    private Path file() { return dir.resolve("justbuild.json"); }
    private JsonObject json() throws Exception { return JsonParser.parseString(Files.readString(file())).getAsJsonObject(); }
    private JsonObject profile(JsonObject json) { return json.getAsJsonArray("profiles").get(2).getAsJsonObject(); }
    private Path keyFile(JsonObject json) { return CredentialKeys.directory().resolve(json.get("credentialKey").getAsString().split(":", 2)[1] + ".key"); }

    @Test void keysAreEncryptedOnDiskAndUsableAfterReload() throws Exception {
        var store = new ConfigStore(file()); var config = config(); store.save(config);
        String disk = Files.readString(file());
        assertFalse(disk.contains(SECRET)); assertFalse(disk.contains("\"apiKey\""));
        assertTrue(profile(json()).get("encryptedApiKey").getAsString().startsWith("aesgcm-v1:"));
        assertEquals(SECRET, store.load().active().apiKey);
        assertEquals(SECRET, ConfigStore.copy(store.load()).active().apiKey);
        assertFalse(keyFile(json()).startsWith(dir));
        assertTrue(Files.exists(keyFile(json())));
    }

    @Test void currentPlaintextProfilesMigrateOnReadWithoutPlaintextBackups() throws Exception {
        Files.writeString(file(), """
                {"profiles":[{"name":"Test","provider":"openai","model":"m",
                "baseUrl":"https://api.example.test/v1","apiKey":"synthetic-api-credential-for-tests"}],"activeProfile":"Test"}
                """);
        var config = new ConfigStore(file()).load();
        assertEquals(SECRET, config.active().apiKey);
        assertFalse(Files.readString(file()).contains(SECRET));
        try (var files = Files.list(dir)) { assertEquals(List.of(file()), files.toList()); }
        assertEquals(SECRET, new ConfigStore(file()).load().active().apiKey);
    }

    @Test void everySaveUsesANewNonceButKeepsTheUnlockingKey() throws Exception {
        var config = config(); var store = new ConfigStore(file()); store.save(config);
        var before = json(); store.save(config); var after = json();
        assertEquals(before.get("credentialKey"), after.get("credentialKey"));
        assertNotEquals(profile(before).get("encryptedApiKey"), profile(after).get("encryptedApiKey"));
        assertEquals(SECRET, store.load().active().apiKey);
    }

    @Test void changedCiphertextNameAndEndpointFailWithoutOverwritingTheFile() throws Exception {
        var store = new ConfigStore(file()); store.save(config());
        JsonObject original = json();
        for (String field : List.of("encryptedApiKey", "name", "baseUrl")) {
            JsonObject changed = original.deepCopy();
            if (field.equals("encryptedApiKey")) {
                String envelope = profile(changed).get(field).getAsString();
                int i = envelope.indexOf(':') + 1;
                profile(changed).addProperty(field, envelope.substring(0, i) + (envelope.charAt(i) == 'A' ? 'B' : 'A') + envelope.substring(i + 1));
            } else profile(changed).addProperty(field, field.equals("name") ? "Changed name" : "https://other.example.test/v1");
            Files.writeString(file(), changed.toString());
            String disk = Files.readString(file());
            var error = assertThrows(ConfigException.class, store::load);
            assertFalse(error.getMessage().contains(SECRET));
            assertEquals(disk, Files.readString(file()));
        }
    }

    @Test void missingVaultDoesNotGenerateAReplacementOrEraseEncryptedKeys() throws Exception {
        var store = new ConfigStore(file()); store.save(config()); var json = json();
        Path key = keyFile(json); Files.delete(key); String disk = Files.readString(file());
        assertThrows(ConfigException.class, store::load);
        assertEquals(disk, Files.readString(file())); assertFalse(Files.exists(key));
    }

    @Test void unavailableVaultLeavesPlaintextKeyIntactForRecovery() throws Exception {
        String disk = "{\"credentialKey\":\"file:00000000-0000-0000-0000-000000000000\","
                + "\"profiles\":[{\"name\":\"Test\",\"provider\":\"openai\",\"model\":\"m\","
                + "\"baseUrl\":\"https://api.example.test/v1\",\"apiKey\":\"" + SECRET + "\"}]}";
        Files.writeString(file(), disk);
        assertThrows(ConfigException.class, () -> new ConfigStore(file()).load());
        assertEquals(disk, Files.readString(file()));
    }

    @Test void profileRenameCopyClearAndEndpointChangesKeepTheirExistingRules() throws Exception {
        var store = new ConfigStore(file()); store.save(config()); var config = store.load();
        var renamed = ProfileEdits.resolve(config.profiles,
                new ProfileEdits.Edit(config.activeProfile, "Renamed", "openai", "m", config.active().baseUrl, null));
        var copied = renamed.copy(); copied.name = "Copy";
        config.replaceProfiles(List.of(renamed, copied), "Renamed"); store.save(config);
        config = store.load(); assertEquals(SECRET, config.active().apiKey); assertEquals(SECRET, config.profiles.get(1).apiKey);
        var changed = ProfileEdits.resolve(config.profiles,
                new ProfileEdits.Edit("Renamed", "Renamed", "openai", "m", "https://other.example.test/v1", null));
        var cleared = ProfileEdits.resolve(config.profiles,
                new ProfileEdits.Edit("Copy", "Copy", "openai", "m", copied.baseUrl, ""));
        config.replaceProfiles(List.of(changed, cleared), "Renamed"); store.save(config);
        assertTrue(store.load().profiles.stream().noneMatch(JustBuildConfig.ModelProfile::hasKey));
        assertFalse(Files.readString(file()).contains("encryptedApiKey"));
    }

    @Test void conflictingPlaintextAndCiphertextAreRejectedAndNotLogged() throws Exception {
        var store = new ConfigStore(file()); store.save(config()); var json = json();
        profile(json).addProperty("apiKey", SECRET); Files.writeString(file(), json.toString());
        var error = assertThrows(ConfigException.class, store::load);
        assertFalse(error.getMessage().contains(SECRET));
        assertEquals(json.toString(), Files.readString(file()));
    }

    @Test void parserFailuresDoNotQuoteCredentialsAndMissingMetadataFailsClosed() throws Exception {
        Files.writeString(file(), "{\"timeoutSeconds\":\"" + SECRET + "\"}");
        assertFalse(assertThrows(ConfigException.class, () -> new ConfigStore(file()).load()).getMessage().contains(SECRET));
        var store = new ConfigStore(file()); store.save(config()); var json = json();
        profile(json).remove("name"); Files.writeString(file(), json.toString());
        assertThrows(ConfigException.class, store::load);
        assertEquals(json.toString(), Files.readString(file()));
    }

    @Test void futureEnvelopeFormatsAndInvalidKeyFilesFailClosed() throws Exception {
        var store = new ConfigStore(file()); store.save(config()); var original = json();
        var changed = original.deepCopy(); profile(changed).addProperty("encryptedApiKey", "aesgcm-v2:future");
        Files.writeString(file(), changed.toString()); assertThrows(ConfigException.class, store::load);
        Files.writeString(file(), original.toString()); Files.write(keyFile(original), new byte[3]);
        assertThrows(ConfigException.class, store::load); assertEquals(original.toString(), Files.readString(file()));
    }

    @Test void failedConfigReplacementCleansTemporaryFilesAndDoesNotChangeTheReference() throws Exception {
        Files.createDirectory(file()); Files.writeString(file().resolve("keep"), "existing directory");
        var config = config(); assertNull(config.credentialKey);
        assertThrows(ConfigException.class, () -> new ConfigStore(file()).save(config));
        assertNull(config.credentialKey); assertTrue(Files.exists(file().resolve("keep")));
        try (var files = Files.list(dir)) { assertEquals(List.of(file()), files.toList()); }
    }

    @Test void keyFilesAndDirectoryArePrivateAndLoosePermissionsAreRefused() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        var store = new ConfigStore(file()); store.save(config()); Path key = keyFile(json());
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(key));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(key.getParent()));
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-r--r--"));
        try { assertThrows(ConfigException.class, store::load); }
        finally { Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------")); }
    }

    @Test void encryptedConfigWorksOnAFileSystemWithoutPermissionsWhileVaultStaysPrivate() throws Exception {
        var uri = java.net.URI.create("jar:" + dir.resolve("game-folder.zip").toUri());
        try (var fs = java.nio.file.FileSystems.newFileSystem(uri, java.util.Map.of("create", "true"))) {
            var store = new ConfigStore(fs.getPath("/config/justbuild.json"));
            store.save(config());
            assertFalse(Files.readString(store.file()).contains(SECRET));
            assertEquals(SECRET, store.load().active().apiKey);
            PrivateFiles.check(CredentialKeys.directory());
        }
    }

    @Test void migrationNeverWritesAnEnvelopeTooLargeForTheReader() throws Exception {
        String disk = "{\"profiles\":[{\"name\":\"Test\",\"provider\":\"openai\",\"model\":\"m\","
                + "\"baseUrl\":\"https://api.example.test/v1\",\"apiKey\":\"" + "x".repeat(12_001) + "\"}]}";
        Files.writeString(file(), disk);
        assertThrows(ConfigException.class, () -> new ConfigStore(file()).load());
        assertEquals(disk, Files.readString(file()));
        var config = config(); config.active().apiKey = "x".repeat(12_000);
        var store = new ConfigStore(file()); store.save(config);
        assertEquals(config.active().apiKey, store.load().active().apiKey);
    }
}
