package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlOwnerSlotSemanticOwnerTest {
    @Test
    void scalarCellSlotCanAcquireBorrowReplaceAndRelease() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash first = new RuntimeHash();
            RuntimeHash second = new RuntimeHash();
            PerlOwnerSlot slot = new PerlOwnerSlot(PerlOwnerSlot.Kind.SCALAR_CELL);

            slot.acquireNativeOwner(first);

            assertSame(first, slot.borrowReferent());
            assertTrue(slot.isNativeOwner());
            assertEquals(1, first.nativeOwnerSlotCount());
            assertEquals(1, runtime.positiveOwnerSlotReferentCount(first));

            slot.replaceNativeOwner(second);

            assertEquals(0, first.nativeOwnerSlotCount());
            assertEquals(0, runtime.positiveOwnerSlotReferentCount(first));
            assertSame(second, slot.borrowReferent());
            assertEquals(1, second.nativeOwnerSlotCount());
            assertEquals(1, runtime.positiveOwnerSlotReferentCount(second));

            slot.release();

            assertNull(slot.borrowReferent());
            assertFalse(slot.isNativeOwner());
            assertTrue(slot.usesNativeOwnerLedger());
            assertEquals(0, second.nativeOwnerSlotCount());
            assertEquals(0, runtime.positiveOwnerSlotReferentCount(second));
        }
    }

    @Test
    void eachClosureCaptureOwnsItsScalarPadCellIndependently() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar padCell = new RuntimeScalar();

            PerlOwnerSlot first = padCell.retainClosureCaptureSlot();
            PerlOwnerSlot second = padCell.retainClosureCaptureSlot();

            assertNotEquals(first.identity(), second.identity());
            assertEquals(2, padCell.captureCount);
            assertTrue(first.isActive());
            assertTrue(second.isActive());
            assertEquals(2, padCell.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE));
            assertEquals(0, runtime.positiveOwnerSlotReferentCount(padCell));

            padCell.releaseClosureCaptureSlot(first);

            assertEquals(1, padCell.captureCount);
            assertFalse(first.isActive());
            assertTrue(second.isActive());
            assertEquals(1, padCell.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE));
            assertFalse(runtime.retainsPositiveOwnerSlotReferent(padCell));

            padCell.releaseClosureCaptureSlot(second);

            assertEquals(0, padCell.captureCount);
            assertFalse(second.isActive());
            assertEquals(0, padCell.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE));
            assertFalse(runtime.retainsPositiveOwnerSlotReferent(padCell));
        }
    }

    @Test
    void aggregateSlotKindsUseIndependentSemanticOwners() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            PerlOwnerSlot arraySlot = new PerlOwnerSlot(PerlOwnerSlot.Kind.ARRAY_ELEMENT);
            PerlOwnerSlot hashSlot = new PerlOwnerSlot(PerlOwnerSlot.Kind.HASH_ELEMENT);

            arraySlot.acquireNativeOwner(referent);
            hashSlot.acquireNativeOwner(referent);

            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));
            assertEquals(2, referent.nativeOwnerSlotCount());
            assertEquals(2, runtime.positiveOwnerSlotReferentCount(referent));

            arraySlot.release();
            assertEquals(1, referent.nativeOwnerSlotCount());
            assertTrue(runtime.retainsPositiveOwnerSlotReferent(referent));

            hashSlot.release();
            assertEquals(0, referent.nativeOwnerSlotCount());
            assertFalse(runtime.retainsPositiveOwnerSlotReferent(referent));
        }
    }

    @Test
    void nativeOwnerCannotReuseADeadReferentOrMigratePendingLegacyReleases() {
        RuntimeHash destroyed = new RuntimeHash();
        destroyed.refCount = Integer.MIN_VALUE;
        PerlOwnerSlot deadSlot = new PerlOwnerSlot(PerlOwnerSlot.Kind.SCALAR_CELL);
        assertThrows(IllegalStateException.class, () -> deadSlot.acquireNativeOwner(destroyed));

        RuntimeHash live = new RuntimeHash();
        live.refCount = 0;
        PerlOwnerSlot pendingSlot = new PerlOwnerSlot(PerlOwnerSlot.Kind.CLOSURE_PAD);
        pendingSlot.acquireLegacyCapture(live);
        assertThrows(IllegalStateException.class, () -> pendingSlot.acquireNativeOwner(live));
        assertEquals(1, live.refCount);
        assertEquals(1, pendingSlot.legacyCaptureCount());
    }
}
