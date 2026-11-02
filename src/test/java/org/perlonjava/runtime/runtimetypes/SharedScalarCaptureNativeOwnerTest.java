package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class SharedScalarCaptureNativeOwnerTest {
    @Test
    void sharedPadCellRetainsOneNativeReferentEdgeUntilItsFinalClosureLeaves() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId(
                    "SharedScalarCaptureNativeOwnerTest::Target");
            RuntimeScalar source = new RuntimeScalar();
            source.set(target.createAnonymousReference());
            RuntimeScalar weakObserver = target.createAnonymousReference();
            WeakRefRegistry.weaken(weakObserver);

            RuntimeScalar capturedPad = new RuntimeScalar(source);
            RuntimeCode firstClosure = code();
            RuntimeCode secondClosure = code();
            PerlOwnerSlot firstCapture = capturedPad.retainClosureCaptureSlot(firstClosure);
            PerlOwnerSlot secondCapture = capturedPad.retainClosureCaptureSlot(secondClosure);
            firstClosure.capturedScalars = new RuntimeScalar[] {capturedPad};
            firstClosure.capturedScalarOwnerSlots = new PerlOwnerSlot[] {firstCapture};
            secondClosure.capturedScalars = new RuntimeScalar[] {capturedPad};
            secondClosure.capturedScalarOwnerSlots = new PerlOwnerSlot[] {secondCapture};

            try {
                assertEquals(2, capturedPad.captureCount);
                assertEquals(1, target.nativeCaptureOwnerCount(),
                        "one captured pad cell owns its value once across multiple closures");
                assertEquals(0, capturedPad.captureRefCountOwned());

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    source.set(new RuntimeScalar());
                    assertTrue(WeakRefRegistry.isweak(weakObserver),
                            "the shared pad cell keeps the target after its source leaves");

                    firstClosure.releaseCaptures();
                    assertFalse(firstCapture.isActive());
                    assertTrue(secondCapture.isActive());
                    assertEquals(1, target.nativeCaptureOwnerCount());
                    assertTrue(WeakRefRegistry.isweak(weakObserver),
                            "one remaining closure still owns the shared captured cell");

                    secondClosure.releaseCaptures();
                    assertFalse(secondCapture.isActive());
                    assertEquals(0, target.nativeCaptureOwnerCount());
                    assertFalse(WeakRefRegistry.isweak(weakObserver),
                            "the final capture release clears weak observers");
                    assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
                    assertEquals(0, measurement.stats().rootQueries,
                            "capture lifetime is decided from owner identities");
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                    assertEquals(0, measurement.stats().externalRootSnapshotsBuilt);
                }
            } finally {
                if (firstClosure.capturedScalars != null) firstClosure.releaseCaptures();
                if (secondClosure.capturedScalars != null) secondClosure.releaseCaptures();
                if (source.refCountOwned) source.set(new RuntimeScalar());
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
            }
        }
    }

    private static RuntimeCode code() {
        return new RuntimeCode((args, context) -> new RuntimeList(), null);
    }
}
