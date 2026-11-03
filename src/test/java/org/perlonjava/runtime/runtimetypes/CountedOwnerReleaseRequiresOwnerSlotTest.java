package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class CountedOwnerReleaseRequiresOwnerSlotTest {
    @Test
    void countedReleaseCannotQueueWithoutItsOwnerIdentity() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeArray referent = new RuntimeArray();
            referent.refCount = 1;

            RuntimeScalar owner = new RuntimeScalar();
            owner.type = RuntimeScalarType.ARRAYREFERENCE;
            owner.value = referent;
            owner.refCountOwned = true;

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> referent.queueOwnerRelease(owner, "invariant regression"));

            assertEquals(1, referent.refCount,
                    "a rejected queue leaves the counted referent unchanged");
            org.junit.jupiter.api.Assertions.assertTrue(
                    failure.getMessage().contains("no active PerlOwnerSlot"));
        }
    }
}
