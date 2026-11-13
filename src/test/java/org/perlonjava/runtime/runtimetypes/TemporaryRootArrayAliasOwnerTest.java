package org.perlonjava.runtime.runtimetypes;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryRootArrayAliasOwnerTest {
    @Test
    void currentArrayAliasOwnerHasCurrentOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar aliasCell = new RuntimeScalar();
            RuntimeArray aliasArray = new RuntimeArray();
            aliasArray.elementsAliased = true;
            aliasArray.elements.add(null);
            aliasArray.setAliasedScalarCell(0, aliasCell);
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            root.elements.add(aliasArray.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                PerlOwnerSlot aliasOwner = aliasCell.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.ARRAY_ALIAS_CELL)
                        .findFirst().orElseThrow();
                assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(aliasCell, aliasOwner),
                        "the array-alias slot must be an active owner");
                assertTrue(aliasArray.ownsReferenceListAliasCellOwner(aliasOwner, aliasCell),
                        "the alias slot must identify the cell currently stored in the array");

            } finally {
                MortalList.popTemporaryRoot(root);
                root.elements.clear();
                root.localBindingExists = false;
                MortalList.scopeExitCleanupArray(root);
                aliasArray.releaseReferenceListAliasCellOwners();
                aliasArray.elements.clear();
            }
        }
    }
}
