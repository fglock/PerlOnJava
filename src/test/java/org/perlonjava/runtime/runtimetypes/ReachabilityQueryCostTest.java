package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ReachabilityQueryCostTest {
    @Test
    void capturedAggregateOwnerReleaseDoesNotScanUnrelatedRoots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            String globalName = "ReachabilityQueryCostTest::captureGlobal";
            GlobalVariable.globalArrays.put(globalName, global);
            try {
                for (int i = 0; i < 256; i++) {
                    global.elements.add(new RuntimeHash().createReference());
                }

                RuntimeArray captured = new RuntimeArray();
                PerlOwnerSlot owner = captured.retainClosureCaptureOwner();
                captured.scopeExited = true;

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    captured.releaseClosureCaptureOwner(owner);

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(0, stats.rootQueries,
                            "native captured-pad release must use its owner slot, not a root proof");
                    assertEquals(0, stats.snapshotsBuilt);
                    assertEquals(0, stats.externalRootSnapshotsBuilt);
                    assertEquals(0, stats.edgesInspected,
                            "unrelated package roots must not add cleanup work");
                    assertEquals(0, stats.externalRootSnapshotEdgesInspected);
                    assertEquals(0, captured.semanticCaptureOwnerCount());
                }
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }

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
                    MyVarCleanupStack.register(target);
                    RuntimeScalar weak = target.createReference();
                    WeakRefRegistry.weaken(weak);
                    target.refCount = 0;
                    target.clearedOwnedAggregateElement = true;

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

    @Test
    void repeatedDeferredCleanupCountsExternalRootSnapshotWork() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            String globalName = "ReachabilityQueryCostTest::deferredGlobal";
            GlobalVariable.globalArrays.put(globalName, global);
            try {
                for (int i = 0; i < 256; i++) {
                    global.elements.add(new RuntimeHash().createReference());
                }

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    for (int i = 0; i < 3; i++) {
                        RuntimeHash target = new RuntimeHash();
                        target.blessId = NameNormalizer.getBlessId(
                                "Issue1649::DeferredRootTarget");
                        global.elements.add(target.createAnonymousReference());
                        RuntimeScalar weak = target.createAnonymousReference();
                        WeakRefRegistry.weaken(weak);
                        target.refCount = 1;

                        MortalList.deferDecrement(target);
                        MortalList.flush();

                        assertEquals(1, target.refCount,
                                "a strong package-root edge must protect the weak target");
                    }

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(3, stats.deferredBasesProcessed,
                            "each deferred release must reach processDeferredBase");
                    assertEquals(3, stats.externalRootSnapshotsBuilt,
                            "each separate flush rebuilds the external-root graph");
                    assertTrue(stats.externalRootSnapshotEdgesInspected >= 3 * 256,
                            "each deferred cleanup re-inspects the unrelated root array");
                    assertTrue(stats.externalRootSnapshotNodesVisited >= 3 * 257,
                            "each deferred cleanup revisits the root and its 256 children");
                }
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }
}
