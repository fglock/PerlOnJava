package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
class DestroySelfOwnerReleaseTest {
    @Test
    void syntheticSelfArgumentRetiresItsSlotWithoutReenteringDestroy() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.refCount = 0;
            RuntimeScalar selfArgument = referent.createAnonymousReference();
            RuntimeScalar.incrementRefCountForContainerStore(
                    selfArgument, PerlOwnerSlot.Kind.ARRAY_ELEMENT);
            PerlOwnerSlot ownerSlot = selfArgument.countedOwnerSlot();

            referent.currentlyDestroying = true;
            MortalList.releaseDestroySelfArgument(
                    selfArgument, "DestroySelfOwnerReleaseTest");

            assertEquals(0, referent.refCount);
            assertEquals(0, referent.countedOwnerSlotCount());
            assertFalse(selfArgument.refCountOwned);
            assertNull(ownerSlot.referent());
            assertFalse(referent.destroyFired);
        }
    }
}
