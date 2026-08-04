package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
class GlobalContextTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void userInstallLibraryIsAddedBeforeDirectoryExists() {
        Path userHome = temporaryDirectory.resolve("clean-user-home");
        Path perlonjavaHome = userHome.resolve(".perlonjava");
        Path userLibrary = perlonjavaHome.resolve("lib");
        assertFalse(userLibrary.toFile().exists());

        var inc = new ArrayList<RuntimeScalar>();
        GlobalContext.addUserLibraryPath(inc, perlonjavaHome);

        assertEquals(1, inc.size());
        assertEquals(userLibrary.toString(), inc.getFirst().toString());
    }

    @Test
    void explicitPerlOnJavaHomeOverridesDefaultUserHome() {
        Path override = temporaryDirectory.resolve("isolated-perlonjava");
        Path userHome = temporaryDirectory.resolve("default-user-home");

        Path resolved = GlobalContext.resolvePerlOnJavaHome(
                Map.of("PERLONJAVA_HOME", override.toString()), userHome.toString());

        assertEquals(override, resolved);
    }

    @Test
    void emptyPerlOnJavaHomeUsesDefaultUserHome() {
        Path userHome = temporaryDirectory.resolve("default-user-home");

        Path resolved = GlobalContext.resolvePerlOnJavaHome(
                Map.of("PERLONJAVA_HOME", ""), userHome.toString());

        assertEquals(userHome.resolve(".perlonjava"), resolved);
    }
}
