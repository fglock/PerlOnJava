package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ReachabilityQueryCostTest {
    @Test
    void repeatedAggregateScopeCleanupCountsActualRootWalkWork() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            for (int i = 0; i < 256; i++) {
                global.elements.add(new RuntimeHash().createReference());
            }
            String globalName = "ReachabilityQueryCostTest::global";
            GlobalVariable.globalArrays.put(globalName, global);

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                for (int i = 0; i < 3; i++) {
                    RuntimeHash target = new RuntimeHash();
                    RuntimeScalar weak = target.createReference();
                    WeakRefRegistry.weaken(weak);
                    target.refCount = 0;
                    target.clearedOwnedAggregateElement = true;

                    MyVarCleanupStack.register(target);
                    MyVarCleanupStack.snapshotStackToLiveCounts();
                    MyVarCleanupStack.unregister(target);

                    assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                            target.perlLifecycleState());
                }

                ReachabilityQueryStats stats = measurement.stats();
                assertEquals(3, stats.rootQueries,
                        "scope cleanup must exercise one real root query per referent");
                assertTrue(stats.rootsSeeded > 0);
                assertTrue(stats.edgesInspected >= 3 * 256,
                        "each cleanup query inspects the unrelated root array edges");
                assertTrue(stats.nodesVisited >= 3 * 257,
                        "each cleanup query revisits the root array and its children");
                assertEquals(0, stats.snapshotsBuilt,
                        "this fallback performs separate targeted walks, not a shared snapshot");
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }
}
