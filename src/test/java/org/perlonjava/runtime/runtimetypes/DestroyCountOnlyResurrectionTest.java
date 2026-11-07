package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class DestroyCountOnlyResurrectionTest {
    @Test
    void positiveCountDoesNotResurrectWithoutALiveOwnerSlot() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.refCount = 1;

            assertTrue(referent.ownerSlotSnapshot().isEmpty());
            assertFalse(DestroyDispatch.hasEscapedPerlOwner(referent, false, null),
                    "a count without a current Perl owner is not resurrection");

            PerlOwnerSlot owner = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.TIED_HASH_ITERATOR_KEY);
            owner.acquireNativeOwner(referent);
            try {
                assertTrue(DestroyDispatch.hasEscapedPerlOwner(referent, false, null),
                        "a live native Perl owner identity is resurrection evidence");
            } finally {
                owner.release();
            }
        }
    }
}
