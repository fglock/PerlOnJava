package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
class DestroyedAggregateStaleOwnerSlotTest {
    @Test
    void finalHashCleanupRetiresSlotToAlreadyDestroyedAggregate() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash child = new RuntimeHash();
            child.setBlessId(NameNormalizer.getBlessId(
                    "DestroyedAggregateStaleOwnerSlot::HashChild"));
            RuntimeScalar childReference = child.createAnonymousReference();
            RuntimeHash parent = new RuntimeHash();
            parent.put("related_resultsets", childReference);
            RuntimeScalar ownerCell = parent.elements.get("related_resultsets");

            assertEquals(1, child.refCount);
            assertEquals(1,
                    child.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));

            markDestroyedWithStaleOwner(child);
            MortalList.scopeExitCleanupDestroyedHash(parent);
            MortalList.flush();

            assertFalse(ownerCell.refCountOwned);
            assertEquals(0,
                    child.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));
        }
    }

    @Test
    void finalArrayCleanupRetiresSlotToAlreadyDestroyedAggregate() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash blessedMarker = new RuntimeHash();
            blessedMarker.setBlessId(NameNormalizer.getBlessId(
                    "DestroyedAggregateStaleOwnerSlot::Marker"));
            RuntimeHash child = new RuntimeHash();
            RuntimeScalar childReference = child.createAnonymousReference();
            RuntimeArray parent = new RuntimeArray();
            parent.push(childReference);
            RuntimeScalar ownerCell = parent.elements.getFirst();

            assertEquals(1, child.refCount);
            assertEquals(1,
                    child.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));

            markDestroyedWithStaleOwner(child);
            MortalList.scopeExitCleanupDestroyedArray(parent);
            MortalList.flush();

            assertFalse(ownerCell.refCountOwned);
            assertEquals(0,
                    child.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
        }
    }

    @Test
    void scalarReferenceContentsOwnerAlreadyRetiresZeroCountTarget() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash target = new RuntimeHash();
            target.setTrackedRefCount(1);

            RuntimeScalar referenceCell = new RuntimeScalar();
            referenceCell.type = RuntimeScalarType.REFERENCE;
            referenceCell.value = target;
            referenceCell.referencedByScalarReference = true;
            referenceCell.setTrackedRefCount(1);

            RuntimeScalar wrapper = new RuntimeScalar();
            wrapper.type = RuntimeScalarType.REFERENCE;
            wrapper.value = referenceCell;
            wrapper.localBindingExists = true;
            PerlOwnerSlot contentsOwner = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS);
            contentsOwner.acquireScalarReferenceContents(target, referenceCell, wrapper);
            wrapper.scalarReferenceContentsOwner = contentsOwner;

            markDestroyedWithStaleOwner(target);
            wrapper.releaseOwnedScalarReferenceContents();

            assertFalse(contentsOwner.isActive());
            assertEquals(0, target.ownerSlotCount(
                    PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS));
        }
    }

    private static void markDestroyedWithStaleOwner(RuntimeBase referent) {
        referent.beginPerlDestruction();
        referent.markPerlDestroyed();
    }
}
