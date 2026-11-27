package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RecursiveZeroCountLifecycleTest {
    @Test
    void blessedContainerCleanupQueuesZeroCountLifecycleWithoutSyntheticOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId("Issue1649::ZeroCountCleanup");
            target.setTrackedRefCount(0);
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

                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, target.perlLifecycleState());
                assertEquals(0, target.refCount);
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        target.perlLifecycleState());
                assertFalse(target.hasPendingOwnerRelease(),
                        "the lifecycle token completes exactly once");
            } finally {
                if (runtime.lifecycleState.hasMortalMarks()) MortalList.popAndFlush();
            }
        }
    }

    @Test
    void zeroCountLifecycleDoesNotDecrementAReacquiredCountedOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId("Issue2043::ReacquiredOwner");
            target.setTrackedRefCount(0);
            RuntimeScalar discarded = new RuntimeScalar();
            discarded.type = RuntimeScalarType.REFERENCE;
            discarded.value = target;
            RuntimeScalar owner = new RuntimeScalar();

            MortalList.pushMark();
            try {
                MortalList.deferDestroyForContainerClear(java.util.List.of(discarded));
                assertEquals(1, runtime.lifecycleState.pending.size());
                assertTrue(runtime.lifecycleState.pendingOwnerReleases.get(0)
                        .isLifecycleOnlyAtZero());

                owner.set(target.createAnonymousReference());
                assertEquals(1, target.refCount,
                        "storing the new reference acquires one counted owner");

                MortalList.popAndFlush();

                assertEquals(1, target.refCount,
                        "a zero-count lifecycle token must not release a later owner");
                assertTrue(owner.refCountOwned);
                assertNotNull(owner.countedOwnerSlot());
                assertTrue(owner.countedOwnerSlot().isActive());
                assertSame(target, owner.countedOwnerSlot().referent());
                assertFalse(target.perlLifecycleState() == RuntimeBase.PerlLifecycleState.DESTROYED,
                        "the re-acquired referent remains alive");

                MortalList.deferDecrementIfTracked(owner);
                MortalList.flush();
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        target.perlLifecycleState(),
                        "the actual counted owner still controls final cleanup");
            } finally {
                if (runtime.lifecycleState.hasMortalMarks()) MortalList.popAndFlush();
            }
        }
    }
}
