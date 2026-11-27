package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ScalarCaptureNativeOwnerTest {
    @Test
    void borrowedCapturedScalarUsesNativeReferentOwnerWithoutCountBridge() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId(
                    "ScalarCaptureNativeOwnerTest::Target");
            RuntimeScalar source = new RuntimeScalar();
            source.set(target.createAnonymousReference());
            RuntimeScalar weakObserver = target.createAnonymousReference();
            WeakRefRegistry.weaken(weakObserver);

            RuntimeScalar capturedPad = new RuntimeScalar(source);
            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            PerlOwnerSlot captureSlot = capturedPad.retainClosureCaptureSlot(closure);
            closure.capturedScalars = new RuntimeScalar[] {capturedPad};
            closure.capturedScalarOwnerSlots = new PerlOwnerSlot[] {captureSlot};

            try {
                assertFalse(capturedPad.refCountOwned,
                        "the copied pad cell borrows the source's counted value");
                assertEquals(1, target.refCount,
                        "capturing a borrowed cell must not invent a counted reference");
                assertEquals(1, target.nativeCaptureOwnerCount(),
                        "the capture slot directly owns the referent");
                assertEquals(0, capturedPad.captureRefCountOwned(),
                        "no legacy capture count is attached to the referent slot");

                source.set(new RuntimeScalar());
                assertTrue(WeakRefRegistry.isweak(weakObserver),
                        "the native capture keeps the target alive after the source leaves");

                closure.releaseCaptures();

                assertFalse(captureSlot.isActive());
                assertEquals(RuntimeScalarType.UNDEF, weakObserver.type,
                        "the final native capture release clears the weak observer");
            } finally {
                if (closure.capturedScalars != null) closure.releaseCaptures();
                if (source.refCountOwned) source.set(new RuntimeScalar());
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
            }
        }
    }
}
