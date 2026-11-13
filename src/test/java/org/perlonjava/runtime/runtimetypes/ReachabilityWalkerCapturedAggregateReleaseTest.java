package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ReachabilityWalkerCapturedAggregateReleaseTest {
    @Test
    void closurePadOwnerKeepsAnAggregateAliveWithoutSweep() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash captured = new RuntimeHash();
            captured.setTrackedRefCount(1);
            captured.put("value", new RuntimeScalar("alive"));
            captured.scopeExited = true;
            PerlOwnerSlot closureOwner = captured.retainClosureCaptureOwner();

            assertEquals(1, captured.refCount);
            assertEquals(1, captured.semanticCaptureOwnerCount());
            assertEquals("alive", captured.get("value").toString());
            assertTrue(runtime.retainsPositiveOwnerSlotReferent(captured));

            captured.releaseClosureCaptureOwner(closureOwner);
        }
    }

    @Test
    void laterCaptureReconcilesZeroCountWhileOwnersRemain() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash captured = new RuntimeHash();
            captured.setTrackedRefCount(1);
            captured.put("value", new RuntimeScalar("alive"));
            captured.scopeExited = true;
            PerlOwnerSlot firstOwner = captured.retainClosureCaptureOwner();

            // Model a count that reached zero while native capture owners
            // still keep the aggregate alive.
            captured.setTrackedRefCount(0);
            PerlOwnerSlot secondOwner = captured.retainClosureCaptureOwner();

            assertEquals(0, captured.refCount);
            assertEquals(2, captured.semanticCaptureOwnerCount());
            assertEquals(2, captured.captureCount);
            assertEquals("alive", captured.get("value").toString());

            captured.releaseClosureCaptureOwner(secondOwner);
            captured.releaseClosureCaptureOwner(firstOwner);
        }
    }
}
