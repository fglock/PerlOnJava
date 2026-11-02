package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.operators.ReferenceOperators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class BlessTemporaryOwnerProvenanceTest {
    @Test
    void firstBlessQueuesTheTemporaryCountWithItsScalarOwnerIdentity() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar expression = referent.createAnonymousReference();

            ReferenceOperators.bless(expression, new RuntimeScalar(
                    "Issue1649::BlessTemporaryOwner"));

            assertEquals(1, referent.refCount,
                    "the bless expression has one deferred temporary owner");
            assertEquals(1, referent.countedOwnerSlotCount());
            RuntimeBase.PendingOwnerRelease pending =
                    runtime.lifecycleState.pendingOwnerReleases.getLast();
            assertNotNull(pending.ownerSlotRelease,
                    "the temporary decrement carries exact owner-slot provenance");
            assertEquals(PerlOwnerSlot.Kind.SCALAR_CELL,
                    pending.ownerSlotRelease.kind());
            assertFalse(pending.completed.get());

            MortalList.flush();

            assertTrue(pending.completed.get());
            assertEquals(Integer.MIN_VALUE, referent.refCount,
                    "the final temporary release completes lifecycle cleanup");
        }
    }
}
