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

    @Test
    void unchangedPackageRootsReuseExternalSnapshotAcrossDrains() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            String globalName =
                    "ReachabilityQueryCostTest::stableDeferredGlobal";
            GlobalVariable.globalArrays.put(globalName, global);
            RuntimeHash[] targets = new RuntimeHash[4];
            RuntimeScalar[] weakRefs = new RuntimeScalar[targets.length];

            try {
                for (int i = 0; i < 3; i++) {
                    targets[i] = new RuntimeHash();
                    targets[i].blessId = NameNormalizer.getBlessId(
                            "Issue1642::StableDeferredRootTarget");
                    global.elements.add(targets[i].createAnonymousReference());
                    weakRefs[i] = targets[i].createAnonymousReference();
                    WeakRefRegistry.weaken(weakRefs[i]);
                    targets[i].refCount = 1;
                }
                MortalList.invalidateAllRootSnapshots();

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    for (int i = 0; i < 3; i++) {
                        MortalList.deferDecrement(targets[i]);
                        MortalList.flush();

                        assertEquals(1, targets[i].refCount,
                                "a stable package-root edge protects each target");
                    }

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(3, stats.deferredBasesProcessed);
                    assertEquals(1, stats.externalRootSnapshotsBuilt,
                            "separate drains reuse the external-root snapshot while roots stay unchanged");
                    long edgesBeforeMutation = stats.externalRootSnapshotEdgesInspected;

                    targets[3] = new RuntimeHash();
                    targets[3].blessId = NameNormalizer.getBlessId(
                            "Issue1642::StableDeferredRootTarget");
                    weakRefs[3] = targets[3].createAnonymousReference();
                    WeakRefRegistry.weaken(weakRefs[3]);
                    targets[3].refCount = 1;
                    global.elements.add(targets[3].createAnonymousReference());

                    MortalList.deferDecrement(targets[3]);
                    MortalList.flush();

                    assertEquals(1, targets[3].refCount,
                            "a new package-root edge protects the target after invalidation");
                    assertEquals(4, stats.deferredBasesProcessed);
                    assertEquals(2, stats.externalRootSnapshotsBuilt,
                            "a package-root mutation invalidates the reused snapshot");
                    assertTrue(stats.externalRootSnapshotEdgesInspected > edgesBeforeMutation,
                            "the rebuilt snapshot inspects the changed root graph");
                }
            } finally {
                for (RuntimeScalar weakRef : weakRefs) {
                    if (weakRef != null) WeakRefRegistry.unweaken(weakRef);
                }
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    @Test
    void unchangedRootsReuseFullSnapshotAcrossDeferredDrains() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            RuntimeArray lexicalRoot = new RuntimeArray();
            RuntimeScalar weakProbe = new RuntimeHash().createAnonymousReference();
            String globalName =
                    "ReachabilityQueryCostTest::stableFullSnapshotGlobal";
            GlobalVariable.globalArrays.put(globalName, global);
            MyVarCleanupStack.register(lexicalRoot);
            WeakRefRegistry.weaken(weakProbe);
            try {
                for (int i = 0; i < 256; i++) {
                    global.elements.add(new RuntimeHash().createReference());
                }
                MortalList.invalidateAllRootSnapshots();

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    for (int i = 0; i < 3; i++) {
                        RuntimeArray target = new RuntimeArray();
                        target.refCount = 1;
                        target.localBindingExists = false;
                        target.clearedOwnedAggregateElement = true;

                        MortalList.deferDecrement(target);
                        MortalList.flush();

                        assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                                target.perlLifecycleState(),
                                "an unrooted aggregate should be reclaimed");
                    }

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(3, stats.deferredBasesProcessed);
                    assertEquals(1, stats.snapshotsBuilt,
                            "unchanged full roots should be walked once across separate drains");
                    assertTrue(stats.nodesVisited >= 257,
                            "the shared full-root snapshot must account for its graph walk");

                    lexicalRoot.elements.add(new RuntimeHash().createReference());
                    RuntimeArray nextTarget = new RuntimeArray();
                    nextTarget.refCount = 1;
                    nextTarget.localBindingExists = false;
                    nextTarget.clearedOwnedAggregateElement = true;
                    MortalList.deferDecrement(nextTarget);
                    MortalList.flush();

                    assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                            nextTarget.perlLifecycleState());
                    assertEquals(2, stats.snapshotsBuilt,
                            "mutating a lexical root's reachable graph invalidates the full snapshot");
                }
            } finally {
                WeakRefRegistry.unweaken(weakProbe);
                MyVarCleanupStack.unregister(lexicalRoot);
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    @Test
    void deferredOwnerChecksShareOneRootSnapshotPerDrain() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            RuntimeArray nestedOwners = new RuntimeArray();
            global.elements.add(nestedOwners.createReference());
            for (int i = 0; i < 256; i++) {
                global.elements.add(new RuntimeHash().createReference());
            }
            String globalName = "ReachabilityQueryCostTest::nestedOwnerGlobal";
            GlobalVariable.globalArrays.put(globalName, global);

            try {
                MortalList.pushMark();
                RuntimeHash[] targets = new RuntimeHash[3];
                for (int i = 0; i < targets.length; i++) {
                    RuntimeHash target = new RuntimeHash();
                    target.activateOwnerTracking();
                    RuntimeScalar owner = target.createAnonymousReference();
                    RuntimeScalar.incrementRefCountForContainerStore(owner);
                    nestedOwners.elements.add(owner);
                    RuntimeScalar weak = target.createAnonymousReference();
                    WeakRefRegistry.weaken(weak);
                    targets[i] = target;
                    MortalList.deferDecrement(target);
                }

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.popAndFlush();

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(3, stats.deferredBasesProcessed);
                    assertEquals(3, stats.scalarReachabilityQueries,
                            "each deferred referent still validates its counted owner");
                    assertEquals(0, stats.scalarReachabilityRootWalks,
                            "owner scalars below one package root reuse the drain snapshot");
                    assertEquals(1, stats.snapshotsBuilt,
                            "the first nested owner builds one snapshot for the whole drain");
                    for (RuntimeHash target : targets) {
                        assertEquals(1, target.refCount,
                                "a strong package-root path preserves the weak target");
                    }
                }
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }
}
