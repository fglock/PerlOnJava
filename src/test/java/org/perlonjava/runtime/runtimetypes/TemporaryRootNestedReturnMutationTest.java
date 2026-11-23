package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryRootNestedReturnMutationTest {
    @Test
    void returnListMutationPreservesTheControlFlowLeaseUntilTransferCompletes() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray oldReferent = new RuntimeArray();
            RuntimeArray newReferent = new RuntimeArray();
            RuntimeList returnValues = new RuntimeList();
            returnValues.elements.add(oldReferent.createReference());
            RuntimeControlFlowList transfer = new RuntimeControlFlowList(
                    returnValues, "temporary-root-nested-return-mutation.t", 1);

            MortalList.pushTemporaryRoot(transfer);
            try {
                assertEquals(1, transfer.ownerSlotCount(
                        PerlOwnerSlot.Kind.EXPRESSION_TEMPORARY_ROOT));
                assertEquals(oldReferent, ((RuntimeScalar) returnValues.elements.getFirst()).value);

                returnValues.elements.set(0, newReferent.createReference());

                assertEquals(newReferent, ((RuntimeScalar) returnValues.elements.getFirst()).value);

                returnValues.elements.clear();
                assertTrue(returnValues.elements.isEmpty());
            } finally {
                MortalList.popTemporaryRoot(transfer);
            }

            assertEquals(0, transfer.ownerSlotCount(
                    PerlOwnerSlot.Kind.EXPRESSION_TEMPORARY_ROOT));
        }
    }
}
