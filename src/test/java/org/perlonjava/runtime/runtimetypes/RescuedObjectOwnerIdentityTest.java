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
            referent.setTrackedRefCount(0);
            RuntimeScalar global = GlobalVariable.getGlobalVariable(
                    "RescuedObjectOwnerIdentityTest::globalOwner");

            try {
                global.set(referent.createReference());
                assertTrue(DestroyDispatch.hasLivePerlOwnerIdentity(referent));

                {
                    assertTrue(DestroyDispatch.hasLivePerlOwnerIdentity(referent));
                }
            } finally {
                global.set(new RuntimeScalar());
            }
        }
    }

    @Test
    void rescueQueueHasNoOwnerButAnExpressionTemporaryDoes() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash rescued = new RuntimeHash();
            runtime.lifecycleState.rescuedObjects.add(rescued);
            assertFalse(DestroyDispatch.hasLivePerlOwnerIdentity(rescued));
            MortalList.pushTemporaryRoot(rescued);

            try {
                assertTrue(DestroyDispatch.hasLivePerlOwnerIdentity(rescued));
            } finally {
                MortalList.popTemporaryRoot(rescued);
                runtime.lifecycleState.rescuedObjects.remove(rescued);
            }
            assertFalse(DestroyDispatch.hasLivePerlOwnerIdentity(rescued));
        }
    }
}
