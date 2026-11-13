package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlOwnerSlotScalarReferenceContentsTest {
    @Test
    void referencedCellOverwriteRetargetsTheStableContentsOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash previous = new RuntimeHash();
            RuntimeHash replacement = new RuntimeHash();
            previous.refCount = 3;
            replacement.refCount = 3;

            RuntimeScalar referenceCell = new RuntimeScalar();
            referenceCell.type = RuntimeScalarType.REFERENCE;
            referenceCell.value = previous;
            referenceCell.refCount = 1;
            referenceCell.referencedByScalarReference = true;
            RuntimeScalar outerReference = new RuntimeScalar();
            outerReference.type = RuntimeScalarType.REFERENCE;
            outerReference.value = referenceCell;
            outerReference.localBindingExists = true;
            PerlOwnerSlot slot = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS);
            slot.acquireScalarReferenceContents(previous, referenceCell, outerReference);
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
            assertFalse(slot.isActive());
        }
    }

    @Test
    void aliasesToOneCellShareOneCountAndReleaseIndependently() {
        RuntimeHash referent = new RuntimeHash();
        referent.refCount = 3;
        RuntimeScalar referenceCell = new RuntimeScalar();
        referenceCell.type = RuntimeScalarType.REFERENCE;
        referenceCell.value = referent;
        RuntimeScalar first = scalarReferenceOwner(referenceCell);
        RuntimeScalar second = scalarReferenceOwner(referenceCell);
        PerlOwnerSlot firstSlot = new PerlOwnerSlot(
                PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS);
        PerlOwnerSlot secondSlot = new PerlOwnerSlot(
                PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS);

        firstSlot.acquireScalarReferenceContents(referent, referenceCell, first);
        secondSlot.acquireScalarReferenceContents(referent, referenceCell, second);
        first.scalarReferenceContentsOwner = firstSlot;
        second.scalarReferenceContentsOwner = secondSlot;

        assertEquals(4, referent.refCount);
        assertEquals(2, referent.ownerSlotCount(
                PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS));

        first.releaseOwnedScalarReferenceContents();
        assertEquals(4, referent.refCount);
        assertEquals(1, referent.ownerSlotCount(
                PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS));
        second.releaseOwnedScalarReferenceContents();
        assertEquals(3, referent.refCount);
        assertEquals(0, referent.ownerSlotCount(
                PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS));
    }

    @Test
    void deferredFinalAliasReleaseCarriesItsOwnerIdentity() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.refCount = 3;
            RuntimeScalar referenceCell = new RuntimeScalar();
            referenceCell.type = RuntimeScalarType.REFERENCE;
            referenceCell.value = referent;
            RuntimeScalar owner = scalarReferenceOwner(referenceCell);
            PerlOwnerSlot slot = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS);
            slot.acquireScalarReferenceContents(referent, referenceCell, owner);
            owner.scalarReferenceContentsOwner = slot;

            MortalList.pushMark();
            PerlOwnerSlot.PendingRelease release = slot.deferScalarReferenceContentsRelease();
            owner.scalarReferenceContentsOwner = null;

            assertNotNull(release);
            assertEquals(slot.identity(), release.ownerSlotIdentity());
            assertEquals(PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS, release.kind());
            assertFalse(release.isCompleted());
            assertFalse(slot.isActive());
            assertEquals(4, referent.refCount);
            assertEquals(0, referent.ownerSlotCount(
                    PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS));

            MortalList.flushAboveMark();

            assertEquals(3, referent.refCount);
            assertTrue(release.isCompleted());
            MortalList.popMark();
        }
    }

    private static RuntimeScalar scalarReferenceOwner(RuntimeScalar referenceCell) {
        RuntimeScalar owner = new RuntimeScalar();
        owner.type = RuntimeScalarType.REFERENCE;
        owner.value = referenceCell;
        owner.localBindingExists = true;
        return owner;
    }
}
