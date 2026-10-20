package dev.justbuild.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Touches only its own randomly named synthetic item, and deletes that item even if the check fails. */
class NativeCredentialKeysTest extends CredentialVaultTest {
    @Test @EnabledOnOs(OS.WINDOWS)
    void windowsDpapiRoundTripWithProtectedAcl() throws Exception {
        var created = CredentialKeys.create();
        try {
            assertTrue(created.reference().startsWith("dpapi:"));
            assertArrayEquals(created.key(), CredentialKeys.load(created.reference()));
            var file = CredentialKeys.directory().resolve(created.reference().split(":", 2)[1] + ".key");
            for (var path : java.util.List.of(file, file.getParent())) {
                PrivateFiles.check(path);
                var descriptor = com.sun.jna.platform.win32.Advapi32Util.getFileSecurityDescriptor(path.toFile(), false);
                assertNotEquals(0, descriptor.Control & com.sun.jna.platform.win32.WinNT.SE_DACL_PROTECTED);
            }
            assertFalse(Arrays.equals(created.key(), java.nio.file.Files.readAllBytes(file)));
        } finally { Arrays.fill(created.key(), (byte) 0); }
    }

    @Test @EnabledOnOs(OS.MAC)
    @EnabledIfEnvironmentVariable(named = "JUSTBUILD_TEST_KEYCHAIN", matches = "1")
    void macKeychainRoundTrip() throws Exception {
        String id = UUID.randomUUID().toString(); byte[] expected = new byte[32]; Arrays.fill(expected, (byte) 7);
        MacCredentialKeys.create(id, expected);
        try { assertTrue(Arrays.equals(expected, MacCredentialKeys.load(id)), "Synthetic key did not round-trip"); }
        finally { MacCredentialKeys.delete(id); }
    }
}
