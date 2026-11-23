package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class TemporaryControlFlowRootTraversalTest {
    @Test
    void temporaryReturnMarkerOwnsItsReturnValueForTheRootLease() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar returnedReference = referent.createAnonymousReference();
            RuntimeControlFlowList transfer = new RuntimeControlFlowList(
                    returnedReference, "temporary-control-flow-root.t", 1);

            MortalList.pushTemporaryRoot(transfer);
            try {
                assertEquals(returnedReference, transfer.getReturnValue());
                assertEquals(1, transfer.ownerSlotCount(
                        PerlOwnerSlot.Kind.EXPRESSION_TEMPORARY_ROOT));
            } finally {
                MortalList.popTemporaryRoot(transfer);
            }
            assertEquals(0, transfer.ownerSlotCount(
                    PerlOwnerSlot.Kind.EXPRESSION_TEMPORARY_ROOT));
        }
    }
}
