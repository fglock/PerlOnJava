package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryRootNestedReturnMutationTest {
    @Test
    void returnListMutationChangesTheReferentsKeptByItsTemporaryRoot() {
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
                assertTrue(MortalList.temporaryRootDirectlyReferences(oldReferent));
                assertFalse(MortalList.temporaryRootDirectlyReferences(newReferent));

                returnValues.elements.set(0, newReferent.createReference());

                assertFalse(MortalList.temporaryRootDirectlyReferences(oldReferent));
                assertTrue(MortalList.temporaryRootDirectlyReferences(newReferent));

                returnValues.elements.clear();

                assertFalse(MortalList.temporaryRootDirectlyReferences(newReferent));
            } finally {
                MortalList.popTemporaryRoot(transfer);
            }

            assertFalse(MortalList.temporaryRootDirectlyReferences(oldReferent));
            assertFalse(MortalList.temporaryRootDirectlyReferences(newReferent));
        }
    }
}
