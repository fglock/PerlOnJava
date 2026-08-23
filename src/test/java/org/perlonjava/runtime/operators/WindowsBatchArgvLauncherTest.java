package org.perlonjava.runtime.operators;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class WindowsBatchArgvLauncherTest {
    @Test
    void jperlBatchBypassesCmdAndPreservesQuotedPerlArgv() {
        String script = "C:\\phase 36\\jperl.bat";
        String program = "print \"out\\n\";\nwarn \"err\\n\"";
        List<String> javaCommand = List.of(
                "C:\\Java\\bin\\java.exe",
                "-cp",
                "perlonjava.jar",
                "org.perlonjava.app.cli.Main");
        Map<String, String> environment = new HashMap<>();

        List<String> command = WindowsBatchArgvLauncher.resolveCommand(
                script, List.of("-e", program), javaCommand, environment);

        assertEquals(List.of(
                "C:\\Java\\bin\\java.exe",
                "-cp",
                "perlonjava.jar",
                "org.perlonjava.app.cli.Main",
                "-e",
                program), command);
        assertFalse(command.contains("cmd.exe"));
        assertFalse(command.contains(script));
        assertEquals(Map.of(), environment);
    }

    @Test
    void acceptsTheMultilineRegexPayloadHandledAfterCommandParsing() {
        assertDoesNotThrow(() -> WindowsBatchArgvLauncher.rejectDelayedExpansionHazard(
                "warn \"===\\n\"; split /[.;]+['\"]+/\nnext line", "batch argument"));
    }

    @Test
    void rejectsBangBeforeTheTargetBatchCanSilentlyExpandIt() {
        assertThrows(IllegalArgumentException.class,
                () -> WindowsBatchArgvLauncher.rejectDelayedExpansionHazard(
                        "C:\\phase!36\\launcher.bat", "batch script path"));
        assertThrows(IllegalArgumentException.class,
                () -> WindowsBatchArgvLauncher.rejectDelayedExpansionHazard(
                        "literal!argument", "batch argument"));
    }
}
