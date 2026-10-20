package dev.justbuild.config;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/** Unlocking keys live outside the game directory. No API credential is stored in this vault. */
final class CredentialKeys {
    static final String DIRECTORY_PROPERTY = "justbuild.credentialsDir";
    private static final SecureRandom RANDOM = new SecureRandom();
    record Created(String reference, byte[] key) { }

    private CredentialKeys() { }

    static Created create() throws ConfigException {
        byte[] key = new byte[32]; RANDOM.nextBytes(key);
        String backend = Platform.isWindows() ? "dpapi" : System.getProperty(DIRECTORY_PROPERTY) != null ? "file"
                : Platform.isMac() ? "keychain" : "file";
        String id = UUID.randomUUID().toString();
        try {
            if (backend.equals("keychain")) MacCredentialKeys.create(id, key);
            else {
                Path directory = directory();
                PrivateFiles.directory(directory);
                byte[] stored = backend.equals("dpapi")
                        ? Crypt32Util.cryptProtectData(key, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN) : key.clone();
                try { PrivateFiles.create(directory.resolve(id + ".key"), stored); }
                finally { Arrays.fill(stored, (byte) 0); }
            }
            return new Created(backend + ":" + id, key);
        } catch (IOException | RuntimeException | LinkageError e) {
            Arrays.fill(key, (byte) 0);
            throw new ConfigException("Could not create the API-key vault. Check your OS credential store or the private "
                    + "directory set by -D" + DIRECTORY_PROPERTY + ". The config was not changed.");
        }
    }

    static byte[] load(String reference) throws ConfigException {
        if (reference == null || !reference.matches("(keychain|dpapi|file):[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new ConfigException("The encrypted API keys have an invalid vault reference. Restore a valid config backup.");
        }
        String[] parts = reference.split(":", 2);
        byte[] key = null;
        try {
            if (parts[0].equals("keychain")) {
                if (!Platform.isMac()) throw new IOException("Wrong platform");
                key = MacCredentialKeys.load(parts[1]);
            } else {
                if (parts[0].equals("dpapi") && !Platform.isWindows()) throw new IOException("Wrong platform");
                Path file = directory().resolve(parts[1] + ".key");
                PrivateFiles.check(file.getParent());
                PrivateFiles.check(file);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16_384) {
                    throw new IOException("Invalid key file");
                }
                byte[] stored = Files.readAllBytes(file);
                try {
                    key = parts[0].equals("dpapi")
                            ? Crypt32Util.cryptUnprotectData(stored, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN) : stored.clone();
                } finally { Arrays.fill(stored, (byte) 0); }
            }
            if (key == null || key.length != 32) throw new IOException("Invalid key length");
            return key;
        } catch (IOException | RuntimeException | LinkageError e) {
            if (key != null) Arrays.fill(key, (byte) 0);
            throw new ConfigException("Cannot unlock the saved API keys. Restore the matching credential vault or unlock "
                    + "the original OS account/keychain. To replace a lost key, remove the affected encryptedApiKey "
                    + "entries and credentialKey from a copy of the config, then enter the keys again. The config was not changed.");
        }
    }

    static Path directory() {
        String override = System.getProperty(DIRECTORY_PROPERTY);
        if (override != null) {
            Path path = Path.of(override);
            if (!path.isAbsolute()) throw new IllegalArgumentException("Credential directory must be absolute");
            return path.normalize();
        }
        Path home = Path.of(System.getProperty("user.home"));
        if (Platform.isWindows()) return home.resolve("AppData/Local/JustBuild/credentials");
        return home.resolve(".local/share/justbuild/credentials");
    }
}
