package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
class GlobalContextTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void userInstallLibraryIsAddedBeforeDirectoryExists() {
        Path userHome = temporaryDirectory.resolve("clean-user-home");
        Path userLibrary = userHome.resolve(".perlonjava").resolve("lib");
        assertFalse(userLibrary.toFile().exists());

        var inc = new ArrayList<RuntimeScalar>();
        GlobalContext.addUserLibraryPath(inc, userHome.toString());

        assertEquals(1, inc.size());
        assertEquals(userLibrary.toString(), inc.getFirst().toString());
    }
}
