package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("unit")
class RecursiveZeroCountLifecycleTest {
    @Test
    void blessedContainerCleanupQueuesZeroCountLifecycleWithoutSyntheticOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId("Issue1649::ZeroCountCleanup");
            target.refCount = 0;
            RuntimeScalar discarded = new RuntimeScalar();
            discarded.type = RuntimeScalarType.REFERENCE;
            discarded.value = target;

            MortalList.pushMark();
            try {
                MortalList.deferDestroyForContainerClear(java.util.List.of(discarded));

                assertEquals(0, target.refCount,
                        "recursive cleanup must not manufacture a positive owner count");
                assertEquals(1, runtime.lifecycleState.pending.size());
                assertNotNull(runtime.lifecycleState.pendingOwnerReleases.get(0),
                        "the lifecycle boundary carries an explicit completion token");

                MortalList.popAndFlush();

                assertEquals(Integer.MIN_VALUE, target.refCount);
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        target.perlLifecycleState());
                assertFalse(target.hasPendingOwnerRelease(),
                        "the lifecycle token completes exactly once");
            } finally {
                if (runtime.lifecycleState.hasMortalMarks()) MortalList.popAndFlush();
            }
        }
    }
}
