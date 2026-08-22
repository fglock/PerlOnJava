package org.perlonjava.runtime;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class ForkOpenStateTest {

    @Test
    void currentJavaCommandStopsBeforeExistingPerlArgv() {
        String[] arguments = {
                "-Dfile.encoding=UTF-8",
                "-cp",
                "runtime.jar",
                "org.perlonjava.app.cli.Main",
                "-e",
                "print qq(existing Perl argv\\n)",
                "trailing"
        };

        assertEquals(List.of(
                "C:\\Java\\bin\\java.exe",
                "-Dfile.encoding=UTF-8",
                "-cp",
                "runtime.jar",
                "org.perlonjava.app.cli.Main"),
                ForkOpenState.currentJavaCommand(
                        "C:\\Java\\bin\\java.exe", arguments));
    }
}
