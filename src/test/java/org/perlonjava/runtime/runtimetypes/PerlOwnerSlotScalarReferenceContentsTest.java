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
    void scalarReferenceCopyDoesNotDoubleCountExplicitlyOwnedCellContents() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.refCount = 3;
            RuntimeScalar scalarReferent = new RuntimeScalar();
            scalarReferent.type = RuntimeScalarType.REFERENCE;
            scalarReferent.value = referent;
            scalarReferent.setRefCountOwned(true);
            scalarReferent.refCountOwned = false;

            RuntimeScalar outerReference = new RuntimeScalar();
            outerReference.type = RuntimeScalarType.REFERENCE;
            outerReference.value = scalarReferent;
            RuntimeScalar.incrementRefCountForContainerStore(outerReference);

            assertEquals(3, referent.refCount,
                    "the copied scalar reference does not duplicate its cell's owner");
            assertEquals(0, referent.ownerSlotCount(
                    PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS));
        }
    }

    @Test
    void aliasesToOneCellShareOneReferentCountAcrossRetargetAndRelease() {
        RuntimeHash original = new RuntimeHash();
        RuntimeHash replacement = new RuntimeHash();
        original.refCount = 3;
        replacement.refCount = 3;
        RuntimeScalar referenceCell = new RuntimeScalar();
        referenceCell.type = RuntimeScalarType.REFERENCE;
        referenceCell.value = original;
        PerlOwnerSlot firstAlias = new PerlOwnerSlot(
                PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS);
        PerlOwnerSlot secondAlias = new PerlOwnerSlot(
                PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS);

        firstAlias.acquireScalarReferenceContents(original, referenceCell);
        firstAlias.acquireScalarReferenceContents(original, referenceCell);
        assertEquals(4, original.refCount,
                "reacquiring the same slot identity does not duplicate its count");
        secondAlias.acquireScalarReferenceContents(original, referenceCell);

        assertEquals(4, original.refCount,
                "one scalar cell contributes one referent count regardless of aliases");
        assertEquals(2, original.ownerSlotCount(
                PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS));

        firstAlias.retargetScalarReferenceContents(replacement);
        assertEquals(4, original.refCount,
                "retargeting one alias preserves the cell's original contents owner");
        assertEquals(4, replacement.refCount,
                "the new cell contents acquire one owner");

        secondAlias.retargetScalarReferenceContents(replacement);
        assertEquals(3, original.refCount,
                "the final old-target alias releases the old contents exactly once");
        assertEquals(4, replacement.refCount,
                "retargeting another alias does not duplicate the new contents owner");

        firstAlias.releaseScalarReferenceContents();
        assertEquals(4, replacement.refCount,
                "releasing one alias leaves the shared contents owner active");
        secondAlias.releaseScalarReferenceContents();
        assertEquals(3, replacement.refCount,
                "the final alias releases the shared contents owner exactly once");
    }

    @Test
    void deferringOneAliasReleaseLeavesTheSharedContentsOwned() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.refCount = 3;
            RuntimeScalar referenceCell = new RuntimeScalar();
            referenceCell.type = RuntimeScalarType.REFERENCE;
            referenceCell.value = referent;
            PerlOwnerSlot firstAlias = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS);
            PerlOwnerSlot secondAlias = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS);
            firstAlias.acquireScalarReferenceContents(referent, referenceCell);
            secondAlias.acquireScalarReferenceContents(referent, referenceCell);

            MortalList.pushMark();
            PerlOwnerSlot.PendingRelease release = firstAlias.deferScalarReferenceContentsRelease();

            assertNull(release,
                    "dropping one alias does not enqueue the cell's shared contents release");
            assertEquals(4, referent.refCount);
            assertEquals(1, referent.ownerSlotCount(
                    PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS));

            secondAlias.releaseScalarReferenceContents();
            assertEquals(3, referent.refCount,
                    "the remaining alias releases the contents owner exactly once");
            MortalList.popMark();
        }
    }

    @Test
    void reachableOwnerCountDeduplicatesAliasesToOneScalarCell() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.refCount = 3;
            RuntimeScalar referenceCell = new RuntimeScalar();
            referenceCell.type = RuntimeScalarType.REFERENCE;
            referenceCell.value = referent;
            RuntimeScalar firstAlias = new RuntimeScalar();
            RuntimeScalar secondAlias = new RuntimeScalar();
            firstAlias.type = RuntimeScalarType.REFERENCE;
            firstAlias.value = referenceCell;
            secondAlias.type = RuntimeScalarType.REFERENCE;
            secondAlias.value = referenceCell;
            MyVarCleanupStack.register(firstAlias);
            MyVarCleanupStack.register(secondAlias);
            try {
                PerlOwnerSlot firstOwner = new PerlOwnerSlot(
                        PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS,
                        null, null, firstAlias);
                PerlOwnerSlot secondOwner = new PerlOwnerSlot(
                        PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS,
                        null, null, secondAlias);
                firstOwner.acquireScalarReferenceContents(referent, referenceCell);
                secondOwner.acquireScalarReferenceContents(referent, referenceCell);

                assertEquals(1, referent.reachableOwnerCount());
                firstOwner.releaseScalarReferenceContents();
                assertEquals(1, referent.reachableOwnerCount(),
                        "the remaining reachable alias still represents one owner");
                secondOwner.releaseScalarReferenceContents();
                assertEquals(0, referent.reachableOwnerCount());
            } finally {
                MyVarCleanupStack.unregister(firstAlias);
                MyVarCleanupStack.unregister(secondAlias);
            }
        }
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
