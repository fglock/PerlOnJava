package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlOwnerSlotScalarReferenceContentsTest {
    @Test
    void referencedCellOverwriteTransfersItsContentsOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash previous = new RuntimeHash();
            RuntimeHash replacement = new RuntimeHash();
            previous.refCount = 3;
            replacement.refCount = 3;

            RuntimeScalar referenceCell = new RuntimeScalar();
            referenceCell.type = RuntimeScalarType.REFERENCE;
            referenceCell.value = previous;
            referenceCell.referencedByScalarReference = true;
            RuntimeScalar outerReference = new RuntimeScalar();
            outerReference.type = RuntimeScalarType.REFERENCE;
            outerReference.value = referenceCell;
            PerlOwnerSlot slot = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS, null, null, outerReference);
            slot.acquireScalarReferenceContents(previous, referenceCell);
            outerReference.scalarReferenceContentsOwner = slot;

            RuntimeScalar replacementValue = new RuntimeScalar();
            replacementValue.type = RuntimeScalarType.REFERENCE;
            replacementValue.value = replacement;
            referenceCell.set(replacementValue);

            assertEquals(3, previous.refCount);
            assertEquals(4, replacement.refCount);
            assertSame(replacement, slot.referent());
            assertEquals(0, previous.ownerSlotCount(
                    PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS));
            assertEquals(1, replacement.ownerSlotCount(
                    PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS));

            outerReference.releaseOwnedScalarReferenceContents();
            assertEquals(3, replacement.refCount);
        }
    }

    @Test
    void acquireAndReleaseUseTheScalarContentsSlotIdentity() {
        RuntimeHash referent = new RuntimeHash();
        referent.refCount = 3;
        PerlOwnerSlot slot = new PerlOwnerSlot(
                PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS);

        slot.acquireScalarReferenceContents(referent);

        assertEquals(4, referent.refCount);
        assertEquals(1, referent.ownerSlotCount(
                PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS));
        assertSame(slot, referent.ownerSlotSnapshot().iterator().next());
        assertTrue(slot.isActive());

        slot.releaseScalarReferenceContents();

        assertEquals(3, referent.refCount);
        assertEquals(0, referent.ownerSlotCount(
                PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS));
        assertFalse(slot.isActive());
        slot.releaseScalarReferenceContents();
        assertEquals(3, referent.refCount);
    }

    @Test
    void deferredReleaseCarriesTheOwnerSlotIdentityUntilDrain() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.refCount = 3;
            PerlOwnerSlot slot = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS);
            slot.acquireScalarReferenceContents(referent);

            MortalList.pushMark();
            PerlOwnerSlot.PendingRelease release = slot.deferScalarReferenceContentsRelease();

            assertNotNull(release);
            assertEquals(slot.identity(), release.ownerSlotIdentity());
            assertEquals(PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS, release.kind());
            assertFalse(release.isCompleted());
            assertEquals(4, referent.refCount);
            assertTrue(slot.isActive());
            assertEquals(1, referent.ownerSlotCount(
                    PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS));

            MortalList.flushAboveMark();

            assertEquals(3, referent.refCount);
            assertTrue(release.isCompleted());
            assertFalse(slot.isActive());
            assertEquals(0, referent.ownerSlotCount(
                    PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS));
            MortalList.popMark();
        }
    }

    @Test
    void rejectsOverflowBeforeRegisteringTheOwnerSlot() {
        RuntimeHash referent = new RuntimeHash();
        referent.refCount = Integer.MAX_VALUE;
        PerlOwnerSlot slot = new PerlOwnerSlot(
                PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS);

        assertThrows(IllegalStateException.class,
                () -> slot.acquireScalarReferenceContents(referent));
        assertEquals(Integer.MAX_VALUE, referent.refCount);
        assertEquals(0, referent.ownerSlotCount(
                PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS));
        assertNull(slot.referent());
    }
}
