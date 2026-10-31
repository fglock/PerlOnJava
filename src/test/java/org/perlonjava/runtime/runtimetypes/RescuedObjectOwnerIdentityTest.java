package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RescuedObjectOwnerIdentityTest {
    @Test
    void durableGlobalSlotIsRecognizedWithoutWalkingRoots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.refCount = 0;
            RuntimeScalar global = GlobalVariable.getGlobalVariable(
                    "RescuedObjectOwnerIdentityTest::globalOwner");

            try {
                global.set(referent.createReference());
                assertTrue(DestroyDispatch.hasLivePerlOwnerIdentity(referent));

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    assertTrue(DestroyDispatch.hasLivePerlOwnerIdentity(referent));
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                    assertEquals(0, measurement.stats().externalRootSnapshotsBuilt);
                }
            } finally {
                global.set(new RuntimeScalar());
            }
        }
    }

    @Test
    void rescueQueueAndTemporaryRootDoNotCountAsPerlOwners() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash rescued = new RuntimeHash();
            runtime.lifecycleState.rescuedObjects.add(rescued);
            MortalList.pushTemporaryRoot(rescued);

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                assertFalse(DestroyDispatch.hasLivePerlOwnerIdentity(rescued));
                assertEquals(0, measurement.stats().rootQueries);
                assertEquals(0, measurement.stats().snapshotsBuilt);
            } finally {
                MortalList.popTemporaryRoot(rescued);
                runtime.lifecycleState.rescuedObjects.remove(rescued);
            }
        }
    }
}
