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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryRootUntrackedScalarOwnerTest {
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
    void currentUntrackedScalarOwnerProvesTemporaryRootWithoutGraphWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar target = new RuntimeScalar("untracked target");
            RuntimeScalar scalarOwner = new RuntimeScalar();
            scalarOwner.type = RuntimeScalarType.REFERENCE;
            scalarOwner.value = target;
            scalarOwner.localBindingExists = true;
            scalarOwner.refreshUntrackedReferenceOwner();
            PerlOwnerSlot ownerSlot = scalarOwner.untrackedReferenceOwnerSlot();

            CountingHashValues opaqueValues = new CountingHashValues();
            RuntimeHash opaque = new RuntimeHash();
            opaque.elements = opaqueValues;
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            root.elements.add(opaque.createReference());
            root.elements.add(scalarOwner.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                assertNotNull(ownerSlot);
                assertEquals(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE, ownerSlot.kind());
                assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(target, ownerSlot),
                        "the scalar must be the current untracked owner of the target");
                assertFalse(MortalList.temporaryRootDirectlyReferences(target),
                        "the root points to the scalar cell, not directly to its value");
                assertTrue(MortalList.temporaryRootDirectlyReferences(scalarOwner),
                        "the root directly references the scalar cell");

                assertTrue(ReachabilityWalker.isReachableFromTemporaryRoots(target));
                assertEquals(0, opaqueValues.valuesCalls,
                        "the validated untracked-scalar path must avoid the fallback graph walk");
            } finally {
                MortalList.popTemporaryRoot(root);
                root.elements.clear();
                root.localBindingExists = false;
                MortalList.scopeExitCleanupArray(root);
                scalarOwner.localBindingExists = false;
                RuntimeScalar.scopeExitCleanup(scalarOwner);
            }
        }
    }
}
