package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class RuntimeScalarValueOwnerSlotTest {
    @Test
    void scalarAssignmentTransfersOneStableOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray first = new RuntimeArray();
            RuntimeArray second = new RuntimeArray();
            RuntimeScalar scalar = new RuntimeScalar();

            scalar.set(first.createReference());

            PerlOwnerSlot slot = scalar.scalarValueOwnerSlot();
            assertEquals(1, first.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
            assertSame(first, slot.referent());

            scalar.set(second.createReference());

            assertSame(slot, scalar.scalarValueOwnerSlot(),
                    "the scalar cell keeps its owner-slot identity across assignment");
            assertEquals(0, first.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
            assertEquals(1, second.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
            assertSame(second, slot.referent());

            scalar.set(new RuntimeScalar(7));

            assertEquals(0, second.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
            assertFalse(slot.isActive());
        }
    }

    @Test
    void localizedScalarValueKeepsItsOwnerUntilRestore() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray original = new RuntimeArray();
            RuntimeArray localized = new RuntimeArray();
            RuntimeScalar scalar = new RuntimeScalar();
            scalar.set(original.createReference());

            scalar.dynamicSaveState();
            scalar.set(localized.createReference());

            assertEquals(1, original.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE),
                    "localization preserves the saved scalar value owner");
            assertEquals(1, localized.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE),
                    "the localized assignment owns its new referent");

            scalar.dynamicRestoreState();

            assertEquals(1, original.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
            assertEquals(0, localized.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
            assertSame(original, scalar.scalarValueOwnerSlot().referent());
        }
    }

    @Test
    void explicitUndefReleasesTheScalarValueSlotAndItsCountTogether() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar scalar = new RuntimeScalar();
            scalar.set(referent.createReference());

            assertEquals(1, referent.refCount);
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));

            scalar.undefine();

            assertEquals(0, referent.refCount,
                    "explicit undef decrements the count represented by the scalar slot");
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE),
                    "the scalar slot is retired with its count");
        }
    }
}
