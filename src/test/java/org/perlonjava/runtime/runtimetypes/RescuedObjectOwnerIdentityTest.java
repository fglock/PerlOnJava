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
    void rescueQueueDoesNotOwnButTemporaryExpressionDoes() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash rescued = new RuntimeHash();
            rescued.refCount = 0;
            runtime.lifecycleState.rescuedObjects.add(rescued);

            assertFalse(DestroyDispatch.hasLivePerlOwnerIdentity(rescued),
                    "the rescue queue alone is not a Perl owner");

            MortalList.pushTemporaryRoot(rescued);

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                assertTrue(DestroyDispatch.hasLivePerlOwnerIdentity(rescued),
                        "a temporary expression is a direct Perl owner");
                assertEquals(1, rescued.refCount);
                assertEquals(1, rescued.ownerSlotCount(PerlOwnerSlot.Kind.EXPRESSION_VALUE));
                assertEquals(0, measurement.stats().rootQueries);
                assertEquals(0, measurement.stats().snapshotsBuilt);
            } finally {
                MortalList.popTemporaryRoot(rescued);
                runtime.lifecycleState.rescuedObjects.remove(rescued);
            }
            assertEquals(0, rescued.ownerSlotCount(PerlOwnerSlot.Kind.EXPRESSION_VALUE));
            assertEquals(0, rescued.refCount);
        }
    }
}
