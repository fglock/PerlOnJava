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
class TemporaryRootScalarContentsOwnerTest {
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
    void scalarReferenceContentsOwnerProvesTemporaryRootWithoutGraphWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            target.refCount = 3;
            RuntimeScalar scalarCell = new RuntimeScalar();
            scalarCell.type = RuntimeScalarType.REFERENCE;
            scalarCell.value = target;
            scalarCell.refCount = 1;
            scalarCell.referencedByScalarReference = true;

            RuntimeScalar wrapper = new RuntimeScalar();
            wrapper.type = RuntimeScalarType.REFERENCE;
            wrapper.value = scalarCell;
            wrapper.localBindingExists = true;
            PerlOwnerSlot contentsOwner = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS);
            contentsOwner.acquireScalarReferenceContents(target, scalarCell, wrapper);
            wrapper.scalarReferenceContentsOwner = contentsOwner;

            CountingHashValues opaqueValues = new CountingHashValues();
            RuntimeHash opaque = new RuntimeHash();
            opaque.elements = opaqueValues;
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            root.elements.add(wrapper);
            root.elements.add(opaque.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(target, contentsOwner),
                        "the scalar-reference contents slot must describe the current cell");
                assertFalse(MortalList.temporaryRootDirectlyReferences(target),
                        "the root points to the scalar cell, not directly to its value");
                assertTrue(MortalList.temporaryRootDirectlyReferences(scalarCell),
                        "the root's direct edge is the scalar cell");

                assertTrue(ReachabilityWalker.isReachableFromTemporaryRoots(target));
                assertEquals(0, opaqueValues.valuesCalls,
                        "the validated scalar-cell ancestry must avoid the fallback graph walk");
            } finally {
                MortalList.popTemporaryRoot(root);
                root.elements.clear();
                root.localBindingExists = false;
                MortalList.scopeExitCleanupArray(root);
                wrapper.localBindingExists = false;
                wrapper.releaseOwnedScalarReferenceContents();
            }
        }
    }
}
