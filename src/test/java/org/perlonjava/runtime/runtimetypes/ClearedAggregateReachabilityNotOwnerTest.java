package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ClearedAggregateReachabilityNotOwnerTest {
    @Test
    void untrackedRootPathDoesNotReplaceAPerlOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeHash referent = new RuntimeHash();
        RuntimeHash packageRoot = new RuntimeHash();
        RuntimeScalar weakObserver = referenceTo(referent);
        RuntimeScalar untrackedElement = referenceTo(referent);
        String globalName =
                "ClearedAggregateReachabilityNotOwnerTest::packageRoot";

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            GlobalVariable.globalHashes.put(globalName, packageRoot);
            // This intentionally bypasses the normal hash store API. It creates
            // graph reachability without the HASH_ELEMENT owner that production
            // stores must acquire.
            packageRoot.elements = new java.util.LinkedHashMap<>();
            packageRoot.elements.put("referent", untrackedElement);
            MyVarCleanupStack.register(referent);
            MortalList.setActive(true);
            try {
                WeakRefRegistry.weaken(weakObserver);
                referent.refCount = 0;
                referent.clearedOwnedAggregateElement = true;
                MortalList.invalidateAllRootSnapshots();

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    assertTrue(MortalList.isReachableFromExternalRootCached(referent),
                            "the synthetic package graph should still reach the referent");
                    MyVarCleanupStack.unregister(referent);

                    assertFalse(DestroyDispatch.hasLivePerlOwnerIdentity(referent));
                    assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                            referent.perlLifecycleState());
                    assertFalse(WeakRefRegistry.isweak(weakObserver),
                            "graph reachability without an owner slot cannot preserve the observer");
                    assertEquals(1, measurement.stats().externalRootSnapshotsBuilt,
                            "the diagnostic walk still measures the synthetic graph");
                    assertTrue(measurement.stats().externalRootSnapshotNodesVisited > 0);
                }
            } finally {
                MyVarCleanupStack.unregister(referent);
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                WeakRefRegistry.clearWeakRefsTo(referent);
                GlobalVariable.globalHashes.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
                MortalList.setActive(previousMortalListState);
            }
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.HASHREFERENCE;
        scalar.value = referent;
        return scalar;
    }
}
