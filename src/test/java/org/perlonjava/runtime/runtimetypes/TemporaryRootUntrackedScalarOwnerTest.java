package org.perlonjava.runtime.runtimetypes;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryRootUntrackedScalarOwnerTest {
    @Test
    void currentUntrackedScalarOwnerHasCurrentOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar target = new RuntimeScalar("untracked target");
            RuntimeScalar scalarOwner = new RuntimeScalar();
            scalarOwner.type = RuntimeScalarType.REFERENCE;
            scalarOwner.value = target;
            scalarOwner.localBindingExists = true;
            scalarOwner.refreshUntrackedReferenceOwner();
            PerlOwnerSlot ownerSlot = scalarOwner.untrackedReferenceOwnerSlot();
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            root.elements.add(scalarOwner.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                assertNotNull(ownerSlot);
                assertEquals(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE, ownerSlot.kind());
                assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(target, ownerSlot),
                        "the scalar must be the current untracked owner of the target");

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
