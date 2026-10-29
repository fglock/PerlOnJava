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
    void firstWeakRootCheckUsesOwnerSlotWithoutWalkingPackageRoots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray packageRoot = new RuntimeArray();
            String globalName =
                    "PackageNestedUntrackedAggregateReleaseTest::ownerSlotFastPath";
            GlobalVariable.globalArrays.put(globalName, packageRoot);

            RuntimeArray target = new RuntimeArray();
            RuntimeScalar strong = target.createAnonymousReference();
            target.refCount = -1;
            packageRoot.elements.add(strong);
            RuntimeScalar weak = target.createAnonymousReference();
            target.refCount = -1;

            try {
                assertEquals(1,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    WeakRefRegistry.weaken(weak);

                    ReachabilityQueryStats stats = measurement.stats();
                    assertTrue(WeakRefRegistry.isweak(weak));
                    assertEquals(1, stats.rootQueries,
                            "the compatibility entry point still records its owner proof");
                    assertEquals(0, stats.edgesInspected,
                            "an active untracked owner slot avoids scanning package roots");
                    assertEquals(0, stats.nodesVisited);
                    assertEquals(0, stats.rootsSeeded);
                }
            } finally {
                WeakRefRegistry.clearWeakRefsTo(target);
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
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
            target.refCount = -1;
            packageRoot.elements.add(firstOwner);
            RuntimeScalar secondOwner = target.createAnonymousReference();
            target.refCount = -1;
            packageRoot.elements.add(secondOwner);
            RuntimeScalar weak = target.createAnonymousReference();
            target.refCount = -1;

            try {
                assertEquals(2,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                WeakRefRegistry.weaken(weak);

                assertTrue(WeakRefRegistry.isweak(weak));
                assertEquals(1, target.refCount,
                        "the package-root proof installs a temporary compatibility pin");
                assertTrue(target.packageRootRescuedUntrackedAggregate);

                firstOwner.undefine();
                assertTrue(WeakRefRegistry.isweak(weak),
                        "one remaining package owner keeps the weak observer live");
                assertEquals(1,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                secondOwner.undefine();

                assertFalse(WeakRefRegistry.isweak(weak));
                assertNull(weak.value,
                        "the observer is cleared when the final untracked owner leaves");
                assertEquals(Integer.MIN_VALUE, target.refCount);
                assertFalse(target.packageRootRescuedUntrackedAggregate);
            } finally {
                WeakRefRegistry.clearWeakRefsTo(target);
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }
}
