package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
class CodeSlotReleaseBoundaryTest {
    @Test
    void releasingCodeSlotDoesNotFlushAnOlderPendingOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash earlierTarget = new RuntimeHash();
            RuntimeScalar anonymousReference = earlierTarget.createAnonymousReference();
            RuntimeScalar earlierOwner = new RuntimeScalar();
            earlierOwner.set(anonymousReference);

            MortalList.pushMark();
            try {
                MortalList.deferDecrementIfTracked(earlierOwner);
                assertEquals(1, runtime.lifecycleState.pending.size());

                RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
                RuntimeScalar codeSlot = new RuntimeScalar(code);
                codeSlot.retainCodeSlotBinding();
                codeSlot.releaseCodeSlotBinding();

                assertEquals(1, earlierTarget.refCount,
                        "the unrelated pending owner stays live at CODE-slot cleanup");
                assertEquals(1, runtime.lifecycleState.pending.size(),
                        "CODE-slot cleanup completes only its own queued release");
            } finally {
                MortalList.popAndFlush();
            }

            assertEquals(Integer.MIN_VALUE, earlierTarget.refCount,
                    "the pending owner is released at its enclosing boundary");
            assertFalse(earlierTarget.hasPendingOwnerRelease());
        }
    }
}
