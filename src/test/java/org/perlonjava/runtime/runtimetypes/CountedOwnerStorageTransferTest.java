package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class CountedOwnerStorageTransferTest {
    @Test
    void countedScalarOwnerFollowsItsMoveIntoAnArrayElement() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.activateOwnerTracking();
            RuntimeScalar scalar = referent.createAnonymousReference();

            RuntimeScalar.incrementRefCountForContainerStore(scalar);
            assertEquals(PerlOwnerSlot.Kind.SCALAR_CELL, scalar.countedOwnerSlot().kind());

            RuntimeArray array = new RuntimeArray();
            array.elements.add(scalar);

            assertEquals(PerlOwnerSlot.Kind.ARRAY_ELEMENT, scalar.countedOwnerSlot().kind(),
                    "the counted edge identity must follow the scalar into its owning array slot");
            assertEquals(1, referent.refCount,
                    "moving the scalar owner changes provenance without changing its count");
        }
    }
}
