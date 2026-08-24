package org.perlonjava.runtime.operators;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class WindowsBatchArgvLauncherTest {
    @Test
    void jperlBatchDecodesQuotedPerlArgvForDirectMainInvocation() {
        String script = "C:\\phase 36\\jperl.bat";
        String program = "print \"out\\n\";\nwarn \"err\\n\"";
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String[] encoded = List.of(script, "-e", program).stream()
                .map(value -> encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .toArray(String[]::new);

        List<String> decoded = WindowsBatchArgvLauncher.decodeArguments(encoded);
        AtomicReference<String[]> invoked = new AtomicReference<>();
        WindowsBatchArgvLauncher.invokeJperl(
                decoded.subList(1, decoded.size()), invoked::set);

        assertEquals(List.of(script, "-e", program), decoded);
        assertTrue(WindowsBatchArgvLauncher.isJperlBatch(decoded.getFirst()));
        assertArrayEquals(new String[] {"-e", program}, invoked.get());
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
