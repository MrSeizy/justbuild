package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesignTransferTest {
    @TempDir
    Path dir;

    @Test
    void windowsDeviceNamesAreNotLibraryFiles() {
        DesignTransfer library = new DesignTransfer(dir);
        for (String name : new String[] {"CON.justbuild.json", "nul.justbuild.json", "COM1.justbuild.json", "lpt9.justbuild.json",
                "aux.house.justbuild.json"}) {
            var e = assertThrows(java.io.IOException.class, () -> library.read(name), name);
            assertTrue(e.getMessage().contains("name"), e.getMessage());
        }
    }
}
