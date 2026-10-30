package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("unit")
class RuntimeScalarUntrackedOwnerIdentityTest {
    @Test
    void staleLegacyScalarCellCountDoesNotKeepUntrackedOwnerAlive() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.refCount = WeakRefRegistry.WEAKLY_TRACKED;

            RuntimeScalar cell = new RuntimeScalar();
            cell.type = RuntimeScalarType.HASHREFERENCE;
            cell.value = referent;
            cell.refCount = 1;
            cell.referencedByScalarReference = true;
            cell.refreshUntrackedReferenceOwner();

            assertNotNull(cell.untrackedReferenceOwnerSlot(),
                    "legacy compatibility count initially allows the owner edge to be recorded");
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

            assertFalse(referent.hasUntrackedReferenceOwnerSlotIdentity(),
                    "without a live scalar-cell identity, the legacy count is not proof of ownership");
            assertFalse(cell.untrackedReferenceOwnerSlot().isActive());
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
        }
    }
}
