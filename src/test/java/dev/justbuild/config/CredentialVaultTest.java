package dev.justbuild.config;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/** Also isolates direct IDE/JUnit runs, which do not inherit Gradle's JVM properties. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
abstract class CredentialVaultTest {
    @TempDir Path vaultRoot;
    private String previousDirectory;

    @BeforeEach void isolateVault() {
        previousDirectory = System.getProperty(CredentialKeys.DIRECTORY_PROPERTY);
        System.setProperty(CredentialKeys.DIRECTORY_PROPERTY, vaultRoot.resolve("credentials").toString());
    }

    @AfterEach void restoreVault() {
        if (previousDirectory == null) System.clearProperty(CredentialKeys.DIRECTORY_PROPERTY);
        else System.setProperty(CredentialKeys.DIRECTORY_PROPERTY, previousDirectory);
    }
}
