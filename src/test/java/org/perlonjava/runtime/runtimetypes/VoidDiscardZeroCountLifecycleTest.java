package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("unit")
class VoidDiscardZeroCountLifecycleTest {
    @Test
    void zeroCountTemporaryQueuesLifecycleWithoutPositiveCountBridge() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray target = new RuntimeArray();
            RuntimeScalar discarded = target.createAnonymousReference();
            RuntimeList result = new RuntimeList();
            result.elements.add(discarded);

            MortalList.pushMark();
            try {
                MortalList.mortalizeForVoidDiscard(result);

                assertEquals(0, target.refCount,
                        "queuing a zero-count temporary must not fabricate an owner count");
                assertEquals(1, runtime.lifecycleState.pending.size());
                assertNotNull(runtime.lifecycleState.pendingOwnerReleases.get(0),
                        "the deferred lifecycle boundary has an explicit completion token");

                MortalList.popAndFlush();

                assertEquals(Integer.MIN_VALUE, target.refCount);
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        target.perlLifecycleState());
                assertFalse(target.hasPendingOwnerRelease(),
                        "the lifecycle completion token retires exactly once");
            } finally {
                if (runtime.lifecycleState.hasMortalMarks()) MortalList.popAndFlush();
            }
        }
    }
}
