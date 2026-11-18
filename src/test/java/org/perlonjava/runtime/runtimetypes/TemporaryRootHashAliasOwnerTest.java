package org.perlonjava.runtime.runtimetypes;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryRootHashAliasOwnerTest {
    @Test
    void exactHashAliasCellOwnerHasCurrentOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar cell = new RuntimeScalar("aliased hash cell");
            RuntimeHash hashOwner = new RuntimeHash();
            hashOwner.aliasScalarCell("cell", cell);
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            root.elements.add(hashOwner.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                PerlOwnerSlot aliasOwner = cell.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.HASH_ALIAS_CELL)
                        .findFirst().orElseThrow();
                assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(cell, aliasOwner),
                        "the hash alias slot must be a current native owner");
                assertSame(cell, hashOwner.elements.get("cell"),
                        "the hash slot must still contain this exact alias cell");

            } finally {
                MortalList.popTemporaryRoot(root);
                root.elements.clear();
                root.localBindingExists = false;
                MortalList.scopeExitCleanupArray(root);
                hashOwner.elements.clear();
                MortalList.scopeExitCleanupHash(hashOwner);
                RuntimeScalar.scopeExitCleanup(cell);
            }
        }
    }
}
