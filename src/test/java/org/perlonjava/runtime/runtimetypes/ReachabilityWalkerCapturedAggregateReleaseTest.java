package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ReachabilityWalkerCapturedAggregateReleaseTest {
    @Test
    void targetedReleaseKeepsAnAggregateOwnedByAClosurePad() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash captured = new RuntimeHash();
            captured.refCount = 1;
            captured.put("value", new RuntimeScalar("alive"));
            captured.scopeExited = true;
            PerlOwnerSlot closureOwner = captured.retainClosureCaptureOwner();

            ReachabilityWalker.sweepReleasedWeakReferents(
                    Collections.singleton(captured));

            assertEquals(1, captured.refCount);
            assertEquals(1, captured.semanticCaptureOwnerCount());
            assertEquals("alive", captured.get("value").toString());
            assertTrue(runtime.retainsPositiveOwnerSlotReferent(captured));

            captured.releaseClosureCaptureOwner(closureOwner);
        }
    }

    @Test
    void laterCaptureReconcilesAStaleDestroyedSentinelWhileOwnersRemain() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash captured = new RuntimeHash();
            captured.refCount = 1;
            captured.put("value", new RuntimeScalar("alive"));
            captured.scopeExited = true;
            PerlOwnerSlot firstOwner = captured.retainClosureCaptureOwner();

            // A targeted weak sweep can miss the native owner slots and leave
            // this bridge sentinel behind while the captured lexical is live.
            captured.refCount = Integer.MIN_VALUE;
            PerlOwnerSlot secondOwner = captured.retainClosureCaptureOwner();

            assertEquals(0, captured.refCount);
            assertEquals(2, captured.semanticCaptureOwnerCount());
            assertEquals(2, captured.captureCount);
            assertEquals("alive", captured.get("value").toString());

            captured.releaseClosureCaptureOwner(secondOwner);
            captured.releaseClosureCaptureOwner(firstOwner);
        }
    }

    @Test
    void capturedAggregateIsReachedThroughItsLiveClosureNotAsAnIndependentRoot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash capturedValue = new RuntimeHash();
            RuntimeArray capturedPad = new RuntimeArray();
            capturedPad.elements.add(capturedValue.createReference());
            capturedPad.elementsOwned = true;
            capturedPad.reconcileArrayElementOwnerSlots();

            RuntimeCode closure = new RuntimeCode((String) null, java.util.List.of());
            closure.capturedAggregates = new RuntimeBase[] {capturedPad};
            PerlOwnerSlot captureOwner = capturedPad.retainClosureCaptureOwner(closure);
            closure.capturedAggregateOwnerSlots = new PerlOwnerSlot[] {captureOwner};

            assertTrue(runtime.retainsPositiveOwnerSlotReferent(capturedPad));
            assertFalse(ReachabilityWalker.isReachableFromRoots(capturedValue),
                    "the capture pad must not root a closure whose Perl owner has gone away");

            RuntimeScalar closureOwner = new RuntimeScalar(closure);
            MyVarCleanupStack.register(closureOwner);
            try {
                assertTrue(ReachabilityWalker.isReachableFromRoots(capturedValue),
                        "a live Perl closure keeps its captured aggregate contents reachable");
            } finally {
                MyVarCleanupStack.unregister(closureOwner);
            }

            closure.releaseCaptures();
        }
    }
}
