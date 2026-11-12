package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class SuspendedFrameEndBoundaryRootTest {
    @Test
    void suspendedFrameOwnerSlotDefinesLifetimeUntilItsRelease() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash rescued = new RuntimeHash();
            PerlOwnerSlot suspendedOwner = new PerlOwnerSlot(PerlOwnerSlot.Kind.SUSPENDED_FRAME);
            suspendedOwner.acquireSuspendedFrameIdentity(rescued);

            try {
                assertTrue(rescued.hasCurrentPerlOwnerSlotIdentity(),
                        "the suspended frame's current owner slot keeps its referent alive");

                suspendedOwner.release();

                assertFalse(rescued.hasCurrentPerlOwnerSlotIdentity(),
                        "a completed frame retires its exact referent slot");
            } finally {
                if (suspendedOwner.isActive()) suspendedOwner.release();
            }
        }
    }
}
