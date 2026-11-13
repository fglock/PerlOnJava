package org.perlonjava.runtime.io;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.DestroyDispatch;
import org.perlonjava.runtime.runtimetypes.PerlOwnerSlot;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.DestroyDispatch;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ViaLayerSelfOwnerSlotTest {
    @Test
    void trackedPushedObjectHasOneOwnerIdentityUntilLayerRelease() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeArray referent = new RuntimeArray();
            DestroyDispatch.callDestroy(referent);

            ViaLayer.SelfReferenceOwner owner =
                    ViaLayer.SelfReferenceOwner.retain(referent.createReference());
            assertNotNull(owner);
            PerlOwnerSlot slot = owner.ownerSlot();

            assertEquals(1, referent.refCount);
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.IO_VIA_LAYER));
            assertTrue(slot.isNativeOwner());
            assertTrue(slot.isCountedNativeOwner());
            assertSame(referent, slot.referent());

            owner.release();
            owner.release();

            assertFalse(slot.isActive());
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.IO_VIA_LAYER));
            assertFalse(slot.isCountedNativeOwner());
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, referent.perlLifecycleState(),
                    "the counted hold is released exactly once after its owner identity");
            assertEquals(0, referent.refCount);
        }
    }

    @Test
    void untrackedPushedObjectStillHasAnIdentityWithoutCountPromotion() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeArray referent = new RuntimeArray();
            referent.refCount = -1;
            RuntimeScalar untrackedReference = new RuntimeScalar();
            untrackedReference.type = RuntimeScalarType.ARRAYREFERENCE;
            untrackedReference.value = referent;
            ViaLayer.SelfReferenceOwner owner =
                    ViaLayer.SelfReferenceOwner.retain(untrackedReference);

            assertNotNull(owner);
            assertEquals(-1, referent.refCount);
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.IO_VIA_LAYER));

            owner.release();

            assertEquals(-1, referent.refCount);
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.IO_VIA_LAYER));
        }
    }

    @Test
    void destroyedPushedObjectDoesNotAcquireALayerOwner() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeArray referent = new RuntimeArray();
            DestroyDispatch.callDestroy(referent);

            assertNull(ViaLayer.SelfReferenceOwner.retain(referent.createReference()));
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.IO_VIA_LAYER));
        }
    }
}
