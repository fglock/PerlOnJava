package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class CountedOwnerStorageTransferTest {
    @Test
    void countedScalarOwnerFollowsItsMoveIntoAggregateSlots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash arrayReferent = new RuntimeHash();
            arrayReferent.activateOwnerTracking();
            RuntimeScalar arrayScalar = arrayReferent.createAnonymousReference();

            RuntimeScalar.incrementRefCountForContainerStore(arrayScalar);
            assertEquals(PerlOwnerSlot.Kind.SCALAR_CELL, arrayScalar.countedOwnerSlot().kind());

            RuntimeArray array = new RuntimeArray();
            array.elements.add(arrayScalar);

            assertEquals(PerlOwnerSlot.Kind.ARRAY_ELEMENT, arrayScalar.countedOwnerSlot().kind(),
                    "the counted edge identity must follow the scalar into its owning array slot");
            assertEquals(1, arrayReferent.refCount,
                    "moving the scalar owner changes provenance without changing its count");

            RuntimeHash hashReferent = new RuntimeHash();
            hashReferent.activateOwnerTracking();
            RuntimeScalar hashScalar = hashReferent.createAnonymousReference();
            RuntimeScalar.incrementRefCountForContainerStore(hashScalar);

            RuntimeHash hash = new RuntimeHash();
            hash.elements.put("owner", hashScalar);

            assertEquals(PerlOwnerSlot.Kind.HASH_ELEMENT, hashScalar.countedOwnerSlot().kind(),
                    "the counted edge identity must follow the scalar into its owning hash slot");
            assertEquals(1, hashReferent.refCount,
                    "moving the scalar owner changes provenance without changing its count");
        }
    }
}
