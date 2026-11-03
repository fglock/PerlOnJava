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
            referent.hadCountedReference = true;

            RuntimeArray replacement = new RuntimeArray();
            replacement.refCount = 1;

            RuntimeScalar owner = new RuntimeScalar();
            owner.type = RuntimeScalarType.ARRAYREFERENCE;
            owner.value = replacement;
            owner.refCountOwned = true;
            owner.recordCountedOwner(replacement);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> referent.queueOwnerRelease(owner, "invariant regression"));

            assertEquals(1, referent.refCount,
                    "a rejected queue leaves the counted referent unchanged");
            org.junit.jupiter.api.Assertions.assertTrue(
                    failure.getMessage().contains("lost its PerlOwnerSlot"));
        }
    }
}
