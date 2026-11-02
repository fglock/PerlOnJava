package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CountedOwnerStorageTransferTest {
    @Test
    void countedScalarCellRemainsCurrentWhenStoredInAggregateSlots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash arrayReferent = new RuntimeHash();
            arrayReferent.activateOwnerTracking();
            RuntimeScalar arrayScalar = arrayReferent.createAnonymousReference();

            RuntimeScalar.incrementRefCountForContainerStore(arrayScalar);
            assertEquals(PerlOwnerSlot.Kind.SCALAR_CELL, arrayScalar.countedOwnerSlot().kind());

            RuntimeArray array = new RuntimeArray();
            array.elements.add(arrayScalar);

            assertEquals(PerlOwnerSlot.Kind.SCALAR_CELL, arrayScalar.countedOwnerSlot().kind(),
                    "the referent owner belongs to the scalar cell, even when an array stores it");
            assertTrue(arrayScalar.isCurrentCountedScalarOwner(
                    arrayReferent, arrayScalar.countedOwnerSlot()),
                    "the current array storage proves the scalar cell is a live Perl owner");
            assertEquals(1, arrayReferent.refCount,
                    "moving the scalar owner changes provenance without changing its count");

            RuntimeHash hashReferent = new RuntimeHash();
            hashReferent.activateOwnerTracking();
            RuntimeScalar hashScalar = hashReferent.createAnonymousReference();
            RuntimeScalar.incrementRefCountForContainerStore(hashScalar);

            RuntimeHash hash = new RuntimeHash();
            hash.elements.put("owner", hashScalar);

            assertEquals(PerlOwnerSlot.Kind.SCALAR_CELL, hashScalar.countedOwnerSlot().kind(),
                    "the referent owner belongs to the scalar cell, even when a hash stores it");
            assertTrue(hashScalar.isCurrentCountedScalarOwner(
                    hashReferent, hashScalar.countedOwnerSlot()),
                    "the current hash storage proves the scalar cell is a live Perl owner");
            assertEquals(1, hashReferent.refCount,
                    "moving the scalar owner changes provenance without changing its count");
        }
    }
}
