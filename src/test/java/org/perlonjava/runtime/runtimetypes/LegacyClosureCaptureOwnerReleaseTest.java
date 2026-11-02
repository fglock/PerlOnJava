package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class LegacyClosureCaptureOwnerReleaseTest {
    @Test
    void finalReleaseWaitsForSemanticCaptureSlotWithoutFabricatingARefCount() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String className = "LegacyCaptureOwnerRelease::Tracked";
            RuntimeHash referent = new RuntimeHash();
            referent.blessId = NameNormalizer.getBlessId(className);
            referent.refCount = 1;
            installDestroy(className);

            RuntimeScalar pad = referenceTo(referent);
            pad.refCountOwned = true;
            pad.retainClosureCapture();
            assertEquals(1, referent.semanticCaptureOwnerCount());
            assertEquals(0, referent.nativeCaptureOwnerCount());

            RuntimeScalar weak = referenceTo(referent);
            WeakRefRegistry.weaken(weak);

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                MortalList.deferDecrement(referent);
                MortalList.flush();

                assertEquals(0, referent.refCount,
                        "a live capture is represented by its owner identity");
                assertTrue(referent.deferredFinalReleaseForUncountedOwners,
                        "final cleanup waits for that identity to leave");
                assertTrue(WeakRefRegistry.isweak(weak));

                pad.releaseClosureCapture();

                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        referent.perlLifecycleState());
                assertEquals(RuntimeScalarType.UNDEF, weak.type,
                        "the final owner release clears weak observers");
                assertFalse(referent.deferredFinalReleaseForUncountedOwners);
                assertEquals(0, measurement.stats().rootQueries,
                        "the owner identity drives cleanup without a root query");
                assertEquals(0, measurement.stats().snapshotsBuilt,
                        "the owner identity drives cleanup without a root snapshot");
            }

            GlobalVariable.removeGlobalCodeRefForStashDelete(className + "::DESTROY");
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.HASHREFERENCE;
        scalar.value = referent;
        return scalar;
    }

    private static void installDestroy(String className) {
        RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
        int separator = className.lastIndexOf("::");
        code.packageName = className.substring(0, separator);
        code.subName = "DESTROY";
        GlobalVariable.getGlobalCodeRef(className + "::DESTROY").set(new RuntimeScalar(code));
    }
}
