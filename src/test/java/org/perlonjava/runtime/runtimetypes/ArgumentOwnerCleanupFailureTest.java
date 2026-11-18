package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class ArgumentOwnerCleanupFailureTest {
    @Test
    void cleanupAttemptsLaterActionsAndPreservesTheFirstFailure() {
        IllegalStateException firstFailure = new IllegalStateException("first release failed");
        IllegalArgumentException secondFailure =
                new IllegalArgumentException("second release failed");
        AtomicInteger laterCleanupCount = new AtomicInteger();

        Throwable failure = RuntimeCode.attemptCleanup(null, () -> {
            throw firstFailure;
        });
        failure = RuntimeCode.attemptCleanup(failure, () -> {
            throw secondFailure;
        });
        failure = RuntimeCode.attemptCleanup(failure, laterCleanupCount::incrementAndGet);

        assertSame(firstFailure, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(secondFailure, failure.getSuppressed()[0]);
        assertEquals(1, laterCleanupCount.get());
    }
}
