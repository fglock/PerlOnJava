package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PackageNestedUntrackedAggregateReleaseTest {
    @Test
    void firstWeakTransitionUsesUntrackedOwnerIdentityWithoutWalkingRoots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray packageRoot = new RuntimeArray();
            String globalName =
                    "PackageNestedUntrackedAggregateReleaseTest::ownerSlotFastPath";
            GlobalVariable.globalArrays.put(globalName, packageRoot);

            RuntimeArray target = new RuntimeArray();
            RuntimeScalar strong = target.createAnonymousReference();
            target.markRefCountUntracked();
            packageRoot.elements.add(strong);
            RuntimeScalar weak = target.createAnonymousReference();
            target.markRefCountUntracked();

            try {
                assertEquals(1,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                {
                    WeakRefRegistry.weaken(weak);

                    assertTrue(WeakRefRegistry.isweak(weak));
                }
            } finally {
                WeakRefRegistry.clearWeakRefsTo(target);
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }

    @Test
    void finalUntrackedPackageOwnerClearsWeakObserver() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray packageRoot = new RuntimeArray();
            String globalName =
                    "PackageNestedUntrackedAggregateReleaseTest::packageRoot";
            GlobalVariable.globalArrays.put(globalName, packageRoot);

            RuntimeArray target = new RuntimeArray();
            RuntimeScalar firstOwner = target.createAnonymousReference();
            target.markRefCountUntracked();
            packageRoot.elements.add(firstOwner);
            RuntimeScalar secondOwner = target.createAnonymousReference();
            target.markRefCountUntracked();
            packageRoot.elements.add(secondOwner);
            RuntimeScalar weak = target.createAnonymousReference();
            target.markRefCountUntracked();

            try {
                assertEquals(2,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                WeakRefRegistry.weaken(weak);

                assertTrue(WeakRefRegistry.isweak(weak));
                assertEquals(RuntimeBase.RefCountTrackingState.WEAKLY_TRACKED, target.refCountTrackingState(),
                        "weak tracking does not fabricate a positive owner count");
                assertEquals(2, target.ownerSlotCount(
                        PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                firstOwner.undefine();
                assertTrue(WeakRefRegistry.isweak(weak),
                        "one remaining package owner keeps the weak observer live");
                assertEquals(1,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                secondOwner.undefine();

                assertFalse(WeakRefRegistry.isweak(weak));
                assertNull(weak.value,
                        "the observer is cleared when the final untracked owner leaves");
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, target.perlLifecycleState());
                assertEquals(0, target.refCount);
            } finally {
                WeakRefRegistry.clearWeakRefsTo(target);
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }
}
