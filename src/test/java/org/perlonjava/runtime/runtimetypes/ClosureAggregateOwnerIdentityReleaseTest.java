package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ClosureAggregateOwnerIdentityReleaseTest {
    @Test
    void scopeExitReleasesCaptureDespiteStaleCodeRefCountWithoutWalkingRoots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray capturedPad = new RuntimeArray();
            RuntimeCode closure = new RuntimeCode(null, List.of());
            PerlOwnerSlot padOwner = capturedPad.retainClosureCaptureOwner(closure);
            closure.capturedAggregates = new RuntimeBase[] {capturedPad};
            closure.capturedAggregateOwnerSlots = new PerlOwnerSlot[] {padOwner};
            closure.captureFieldsRecorded = true;

            RuntimeScalar weakCode = new RuntimeScalar(closure);
            WeakRefRegistry.weaken(weakCode);
            closure.refCount = 7; // stale compatibility count, with no strong owner slot
            assertFalse(closure.hasPerlOwnerForCaptureOtherThan(null));

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                MortalList.scopeExitCleanupArray(capturedPad);

                assertNull(closure.capturedAggregates);
                assertNull(closure.capturedAggregateOwnerSlots);
                assertFalse(padOwner.isActive());
                assertEquals(0, capturedPad.captureCount);
                assertEquals(0, measurement.stats().rootQueries);
                assertEquals(0, measurement.stats().snapshotsBuilt);
            } finally {
                WeakRefRegistry.removeWeakRef(weakCode, closure);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    @Test
    void scopeExitKeepsCaptureWhileCodeHasAScalarOwnerIdentity() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray capturedPad = new RuntimeArray();
            RuntimeCode closure = new RuntimeCode(null, List.of());
            closure.refCount = 0;
            PerlOwnerSlot padOwner = capturedPad.retainClosureCaptureOwner(closure);
            closure.capturedAggregates = new RuntimeBase[] {capturedPad};
            closure.capturedAggregateOwnerSlots = new PerlOwnerSlot[] {padOwner};
            closure.captureFieldsRecorded = true;
            closure.capturedScalars = new RuntimeScalar[0];
            RuntimeScalar codeOwner = new RuntimeScalar();
            codeOwner.set(new RuntimeScalar(closure));

            assertTrue(closure.hasPerlOwnerForCaptureOtherThan(null));
            MortalList.scopeExitCleanupArray(capturedPad);

            assertTrue(padOwner.isActive());
            assertEquals(1, capturedPad.captureCount);
            assertTrue(capturedPad.scopeExited);

            codeOwner.set(new RuntimeScalar());
            assertNull(closure.capturedAggregates);
            assertFalse(padOwner.isActive());
        }
    }
}
