package org.perlonjava.runtime.runtimetypes;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryRootArrayAliasOwnerTest {
    private static final class CountingHashValues extends AbstractMap<String, RuntimeScalar> {
        private int valuesCalls;

        @Override
        public Set<Entry<String, RuntimeScalar>> entrySet() {
            return Collections.emptySet();
        }

        @Override
        public Collection<RuntimeScalar> values() {
            valuesCalls++;
            return List.of();
        }
    }

    @Test
    void currentArrayAliasOwnerProvesTemporaryRootWithoutGraphWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar aliasCell = new RuntimeScalar();
            RuntimeArray aliasArray = new RuntimeArray();
            aliasArray.elementsAliased = true;
            aliasArray.elements.add(null);
            aliasArray.setAliasedScalarCell(0, aliasCell);

            CountingHashValues opaqueValues = new CountingHashValues();
            RuntimeHash opaque = new RuntimeHash();
            opaque.elements = opaqueValues;
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            root.elements.add(aliasArray.createReference());
            root.elements.add(opaque.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                PerlOwnerSlot aliasOwner = aliasCell.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.ARRAY_ALIAS_CELL)
                        .findFirst().orElseThrow();
                assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(aliasCell, aliasOwner),
                        "the array-alias slot must be an active owner");
                assertTrue(aliasArray.ownsReferenceListAliasCellOwner(aliasOwner, aliasCell),
                        "the alias slot must identify the cell currently stored in the array");
                assertFalse(MortalList.temporaryRootDirectlyReferences(aliasCell),
                        "the root contains the owning array, not the aliased cell");
                assertTrue(MortalList.temporaryRootDirectlyReferences(aliasArray),
                        "the root directly contains the array that owns the cell");

                assertTrue(ReachabilityWalker.isReachableFromTemporaryRoots(aliasCell));
                assertEquals(0, opaqueValues.valuesCalls,
                        "the validated array-alias path must avoid the fallback graph walk");
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
