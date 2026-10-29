package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class PackageScalarValueOwnerSlotTest {
    @Test
    void trackedReferentGetsOneUncountedIdentityForPackageScalarCell() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar countedOwner = new RuntimeScalar();
            countedOwner.set(referent.createAnonymousReference());

            RuntimeScalar packageCell = referent.createAnonymousReference();
            String globalName =
                    "PackageScalarValueOwnerSlotTest::trackedPackageCell";
            PerlOwnerSlot valueOwner;

            try {
                GlobalVariable.globalVariables.put(globalName, packageCell);
                valueOwner = packageCell.untrackedReferenceOwnerSlot();

                assertNotNull(valueOwner,
                        "the package scalar cell owns its uncounted value edge");
                assertSame(referent, valueOwner.referent());
                assertEquals(1, referent.refCount,
                        "the extra slot identity does not duplicate the legacy count");
                assertEquals(1,
                        referent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                GlobalVariable.globalVariables.remove(globalName);

                assertFalse(valueOwner.isActive(),
                        "removing the package cell releases its value edge once");
                assertEquals(0,
                        referent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                assertEquals(1, referent.refCount,
                        "the independent counted scalar owner remains");

                countedOwner.set(new RuntimeScalar());
                assertEquals(Integer.MIN_VALUE, referent.refCount,
                        "the final counted owner performs final release");
            } finally {
                GlobalVariable.globalVariables.remove(globalName);
                countedOwner.set(new RuntimeScalar());
            }
        }
    }
}
