package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ReachabilityQueryCostTest {
    private static final class CountingStashMap extends AbstractMap<String, RuntimeScalar> {
        private int valuesCalls;

        @Override
        public Set<Entry<String, RuntimeScalar>> entrySet() {
            return Collections.emptySet();
        }

        @Override
        public Collection<RuntimeScalar> values() {
            valuesCalls++;
            return Collections.emptyList();
        }
    }

    @Test
    void repeatedContainerScopeCleanupDoesNotEnumerateNestedStashRoots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeStash stash = new RuntimeStash("ReachabilityQueryCostTest::");
            CountingStashMap stashView = new CountingStashMap();
            stash.elements = stashView;
            ((RuntimeHash) stash).elements = stashView;

            for (int i = 0; i < 3; i++) {
                RuntimeArray container = new RuntimeArray();
                container.elements.add(stash.createReference());

                assertFalse(MortalList.requestWeakSweepsForDestroyedContainer(container));
            }

            assertEquals(0, stashView.valuesCalls,
                    "scope cleanup must not enumerate dynamic stash entries on each container walk");
        }
    }

    @Test
    void aggregateOwnerRecoveryChecksEachSharedContainerOnce() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeHash owner = new RuntimeHash();
            ArrayList<RuntimeScalar> cells = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                RuntimeScalar cell = new RuntimeScalar();
                cell.type = RuntimeScalarType.ARRAYREFERENCE;
                cell.value = referent;
                cell.setRefCountOwned(true);
                cell.refCountOwned = false;
                owner.elements.put("slot" + i, cell);
                cells.add(cell);
            }
            referent.refCount = 0;
            MyVarCleanupStack.register(owner);
            try {
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    assertTrue(MortalList.restoreReachableAggregateOwnerCount(referent));

                    assertEquals(64, referent.refCount,
                            "every rooted hash-entry cell remains an owner");
                    assertEquals(1, measurement.stats().aggregateOwnerRootProofs,
                            "one owner-container proof covers all entries in the same hash");
                    assertEquals(63, measurement.stats().aggregateOwnerRootProofCacheHits,
                            "the remaining entry slots reuse that proof");
                    assertEquals(128, measurement.stats().aggregateOwnerSlotsInspected,
                            "each referent owner slot is inspected once during recovery");
                }
            } finally {
                MyVarCleanupStack.unregister(owner);
                owner.elements.clear();
                for (RuntimeScalar cell : cells) {
                    if (cell.scalarValueOwnerSlot() != null) {
                        cell.scalarValueOwnerSlot().release();
                    }
                }
                referent.refCount = 0;
            }
        }
    }

    @Test
    void scopeExitedCaptureOwnersShareOneScalarRootSnapshot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            ArrayList<RuntimeBase> candidates = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                RuntimeHash referent = new RuntimeHash();
                RuntimeScalar padCell = referent.createReference();
                padCell.retainClosureCapture();
                padCell.scopeExited = true;
                candidates.add(referent);
            }

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                ReachabilityWalker.releaseUnreachableScopeExitedCaptureOwners(
                        candidates, null);

                ReachabilityQueryStats stats = measurement.stats();
                assertEquals(1, stats.scalarReachabilityRootWalks,
                        "one scalar-root walk should cover all stale capture owners");
                assertEquals(1, stats.snapshotsBuilt);
            }
        }
    }

    @Test
    void targetedWeakReleaseSkipsFullWalkerForSnapshotRootedTargets() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            String globalName =
                    "ReachabilityQueryCostTest::targetedWeakReleaseSnapshot";
            GlobalVariable.globalArrays.put(globalName, global);

            RuntimeHash[] targets = new RuntimeHash[8];
            RuntimeScalar[] weakRefs = new RuntimeScalar[targets.length];
            Set<RuntimeBase> released = Collections.newSetFromMap(
                    new java.util.IdentityHashMap<>());
            for (int i = 0; i < targets.length; i++) {
                RuntimeHash target = new RuntimeHash();
                RuntimeScalar strong = target.createReference();
                RuntimeScalar weak = target.createReference();
                WeakRefRegistry.weaken(weak);
                global.elements.add(strong);
                target.refCount = 0;
                targets[i] = target;
                weakRefs[i] = weak;
                released.add(target);
            }

            try {
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    ReachabilityWalker.sweepReleasedWeakReferents(released);

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(0, stats.snapshotsBuilt,
                            "positive package-root proofs should avoid the full weak sweep walker");
                    assertEquals(1, stats.externalRootSnapshotsBuilt,
                            "the package-root graph is snapshotted once for the whole batch");
                    for (int i = 0; i < targets.length; i++) {
                        assertTrue(WeakRefRegistry.isweak(weakRefs[i]));
                        assertTrue(targets[i].refCount != Integer.MIN_VALUE);
                    }
                }
            } finally {
                for (RuntimeScalar weak : weakRefs) {
                    if (weak != null) WeakRefRegistry.unweaken(weak);
                }
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    @Test
    void targetedWeakReleaseDoesNotTreatTemporaryRootsAsPersistentOwners() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray target = new RuntimeArray();
            RuntimeScalar weak = target.createReference();
            WeakRefRegistry.weaken(weak);
            target.refCount = 0;
            target.localBindingExists = false;
            MortalList.pushTemporaryRoot(target);

            try {
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    ReachabilityWalker.sweepReleasedWeakReferents(
                            Collections.singleton(target));

                    assertEquals(RuntimeScalarType.UNDEF, weak.type,
                            "temporary roots do not keep a released weak referent alive");
                    assertEquals(1, measurement.stats().externalRootSnapshotsBuilt,
                            "the temporary-only target must miss the persistent-root snapshot proof");
                }
            } finally {
                MortalList.popTemporaryRoot(target);
                WeakRefRegistry.unweaken(weak);
            }
        }
    }

    @Test
    void scalarOnlyMutationReusesTheAggregateRootSnapshot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            RuntimeHash first = new RuntimeHash();
            RuntimeHash second = new RuntimeHash();
            global.elements.add(first.createReference());
            global.elements.add(second.createReference());
            global.elements.add(new RuntimeScalar("before"));
            String globalName = "ReachabilityQueryCostTest::scalarOnlyMutation";
            GlobalVariable.globalArrays.put(globalName, global);

            try {
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    assertTrue(MortalList.isUnblessedAggregateReachableDuringDrain(first));
                    global.elements.set(2, new RuntimeScalar("after"));
                    assertTrue(MortalList.isUnblessedAggregateReachableDuringDrain(second));

                    assertEquals(1, measurement.stats().externalRootSnapshotsBuilt,
                            "changing a scalar leaf does not change the aggregate root graph");
                }
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

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
    void repeatedAggregateScopeCleanupSharesExternalRootSnapshot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            for (int i = 0; i < 256; i++) {
                global.elements.add(new RuntimeHash().createReference());
            }
            String globalName = "ReachabilityQueryCostTest::global";
            GlobalVariable.globalArrays.put(globalName, global);

            RuntimeHash[] targets = new RuntimeHash[3];
            RuntimeScalar[] weakRefs = new RuntimeScalar[targets.length];
            for (int i = 0; i < targets.length; i++) {
                RuntimeHash target = new RuntimeHash();
                MyVarCleanupStack.register(target);
                RuntimeScalar weak = target.createReference();
                WeakRefRegistry.weaken(weak);
                target.refCount = 0;
                target.clearedOwnedAggregateElement = true;
                targets[i] = target;
                weakRefs[i] = weak;
            }
            MortalList.invalidateAllRootSnapshots();

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                for (RuntimeHash target : targets) {
                    MyVarCleanupStack.unregister(target);

                    assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                            target.perlLifecycleState());
                }

                ReachabilityQueryStats stats = measurement.stats();
                assertEquals(0, stats.rootQueries,
                        "scope cleanup should use the cached package-root snapshot");
                assertEquals(1, stats.externalRootSnapshotsBuilt,
                        "unchanged package roots should be traversed once across scope exits");
                assertTrue(stats.externalRootSnapshotEdgesInspected >= 256,
                        "the shared snapshot must account for the unrelated root array edges");
                assertTrue(stats.externalRootSnapshotNodesVisited >= 257,
                        "the shared snapshot visits the root array and its children once");
            } finally {
                for (RuntimeScalar weak : weakRefs) {
                    if (weak != null) WeakRefRegistry.unweaken(weak);
                }
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
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
    void unchangedPackageRootsReuseSnapshotAndLexicalMutationRefreshesLiveRoots() {
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
                    assertEquals(1, stats.liveRootSnapshotsBuilt,
                            "unchanged lexical roots should share one snapshot across drains");
                    RuntimeArray nextTarget = new RuntimeArray();
                    lexicalRoot.elements.add(nextTarget.createReference());
                    assertTrue(MortalList.isReachableFromExternalRootCached(nextTarget),
                            "a lexical edge mutation must refresh the live-root snapshot");
                    assertEquals(1, stats.externalRootSnapshotsBuilt,
                            "unchanged package roots should share one snapshot across drains");
                    assertEquals(2, stats.liveRootSnapshotsBuilt,
                            "a lexical edge mutation must build a fresh live-root snapshot");
                    assertTrue(stats.externalRootSnapshotNodesVisited >= 257,
                            "the shared package-root snapshot must account for its graph walk");
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
