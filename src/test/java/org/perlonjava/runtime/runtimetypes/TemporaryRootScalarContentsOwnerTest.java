package org.perlonjava.runtime.runtimetypes;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryRootScalarContentsOwnerTest {
    @Test
    void scalarReferenceContentsOwnerHasCurrentOwnerSlot() {
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
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            root.elements.add(wrapper);

            MortalList.pushTemporaryRoot(root);
            try {
                assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(target, contentsOwner),
                        "the scalar-reference contents slot must describe the current cell");

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
