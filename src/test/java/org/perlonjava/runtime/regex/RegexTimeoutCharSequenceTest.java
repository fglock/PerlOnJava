package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class RegexTimeoutCharSequenceTest {

    @Test
    void interruptedMatchIsCancellationRatherThanRegexTimeout() {
        RegexTimeoutCharSequence input = new RegexTimeoutCharSequence("x", 60_000);
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> {
                for (int i = 0; i < 4096; i++) {
                    input.charAt(0);
                }
            });
        } finally {
            Thread.interrupted();
        }
    }
}
