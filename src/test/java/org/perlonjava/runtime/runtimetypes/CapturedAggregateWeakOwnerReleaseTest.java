package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CapturedAggregateWeakOwnerReleaseTest {

    @Test
    void finalCaptureOwnerReleasesWeakSentinelWithoutSyntheticCount() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar weakObserver = referent.createAnonymousReference();
            RuntimeScalar.incrementRefCountForContainerStore(weakObserver);
            PerlOwnerSlot captureOwner = referent.retainClosureCaptureOwner();

            try {
                assertEquals(1, referent.refCount);
                WeakRefRegistry.weaken(weakObserver);

                assertTrue(WeakRefRegistry.isweak(weakObserver));
                assertEquals(WeakRefRegistry.WEAKLY_TRACKED, referent.refCount,
                        "a semantic closure owner preserves lifecycle without a count shim");
                assertTrue(referent.deferredFinalReleaseForUncountedOwners);
                assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.CLOSURE_PAD));

                referent.releaseClosureCaptureOwner(captureOwner);

                assertFalse(WeakRefRegistry.isweak(weakObserver));
                assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
                assertEquals(Integer.MIN_VALUE, referent.refCount,
                        "the final capture owner retires the weak referent exactly once");
                assertFalse(referent.deferredFinalReleaseForUncountedOwners);
            } finally {
                if (captureOwner.isActive()) referent.releaseClosureCaptureOwner(captureOwner);
                WeakRefRegistry.clearWeakRefsTo(referent);
            }
        }
    }
}
