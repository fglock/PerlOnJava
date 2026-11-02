package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class LegacyClosureAggregateReleaseOwnerTest {
    @Test
    void releasingLegacyCaptureDoesNotQueueAnotherOwnersCountedEdge() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray capturedPad = new RuntimeArray();
            capturedPad.blessId = NameNormalizer.getBlessId(
                    "Issue1649::LegacyClosureAggregateReleaseOwner");
            RuntimeScalar countedOwner = new RuntimeScalar();
            countedOwner.set(capturedPad.createReference());
            RuntimeScalar weakObserver = capturedPad.createAnonymousReference();
            WeakRefRegistry.weaken(weakObserver);

            capturedPad.retainClosureCapture();
            MortalList.scopeExitCleanupArray(capturedPad);
            assertTrue(capturedPad.scopeExited,
                    "scope cleanup waits while the closure capture remains active");
            assertEquals(1, capturedPad.refCount,
                    "the scalar cell is the only counted owner");

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                capturedPad.releaseClosureCapture();
                MortalList.flush();

                assertEquals(1, capturedPad.refCount,
                        "releasing a capture must not decrement the scalar cell's edge");
                assertTrue(WeakRefRegistry.isweak(weakObserver),
                        "the remaining scalar cell keeps the weak referent alive");
                assertEquals(0, measurement.stats().deferredBasesProcessed,
                        "capture release must not enqueue a synthetic count decrement");
                assertEquals(0, measurement.stats().rootQueries);
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                countedOwner.set(new RuntimeScalar());
            }
        }
    }
}
