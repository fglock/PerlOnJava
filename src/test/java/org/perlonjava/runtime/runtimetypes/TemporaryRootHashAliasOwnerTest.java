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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryRootHashAliasOwnerTest {
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
    void exactHashAliasCellOwnerProvesTemporaryRootWithoutGraphWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar cell = new RuntimeScalar("aliased hash cell");
            RuntimeHash hashOwner = new RuntimeHash();
            hashOwner.aliasScalarCell("cell", cell);

            CountingHashValues opaqueValues = new CountingHashValues();
            RuntimeHash opaque = new RuntimeHash();
            opaque.elements = opaqueValues;
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            root.elements.add(opaque.createReference());
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
                assertFalse(MortalList.temporaryRootDirectlyReferences(cell),
                        "the root points to the owning hash, not directly to its cell");
                assertTrue(MortalList.temporaryRootDirectlyReferences(hashOwner),
                        "the root directly references the alias-owning hash");

                assertTrue(ReachabilityWalker.isReachableFromTemporaryRoots(cell));
                assertEquals(0, opaqueValues.valuesCalls,
                        "the validated hash-alias path must avoid the fallback graph walk");
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
