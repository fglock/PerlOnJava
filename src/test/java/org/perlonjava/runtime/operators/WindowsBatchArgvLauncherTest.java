package org.perlonjava.runtime.operators;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class WindowsBatchArgvLauncherTest {
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
