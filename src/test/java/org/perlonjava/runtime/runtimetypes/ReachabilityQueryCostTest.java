package org.perlonjava.runtime.runtimetypes;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ReachabilityQueryCostTest {
    @Test
    void directLiveScalarProofPrecedesGraphSnapshots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeScalar root = target.createReference();
            MyVarCleanupStack.register(root);
            MyVarCleanupStack.snapshotStackToLiveCounts();
            try {
                MortalList.LifecycleRootQueryStats stats =
                        new MortalList.LifecycleRootQueryStats();
                assertEquals(MortalList.LifecycleRootProof.DIRECT_SCALAR,
                        MortalList.lifecycleRootProof(target, stats));
                assertTrue(stats.liveScalarsInspected > 0);
                assertEquals(0, stats.externalRootQueries);
                assertEquals(0, stats.targetedWalkQueries);
                assertEquals(0, stats.fullSnapshotBuilds);
            } finally {
                MyVarCleanupStack.unregister(root);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    @Test
    void multipleLifecycleTargetsShareOneFullRootSnapshotPerDrain() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray first = new RuntimeArray();
            RuntimeArray second = new RuntimeArray();
            RuntimeArray third = new RuntimeArray();
            MyVarCleanupStack.register(first);
            MyVarCleanupStack.register(second);
            MyVarCleanupStack.register(third);
            MyVarCleanupStack.snapshotStackToLiveCounts();
            MortalList.invalidateAllRootSnapshots();
            try {
                MortalList.LifecycleRootQueryStats firstStats =
                        new MortalList.LifecycleRootQueryStats();
                assertEquals(MortalList.LifecycleRootProof.FULL_ROOT,
                        MortalList.lifecycleRootProof(first, firstStats));
                assertEquals(1, firstStats.targetedWalkQueries,
                        "the first non-scalar target uses a short-circuit walk");
                assertEquals(0, firstStats.fullSnapshotBuilds);

                MortalList.LifecycleRootQueryStats secondStats =
                        new MortalList.LifecycleRootQueryStats();
                assertEquals(MortalList.LifecycleRootProof.FULL_ROOT,
                        MortalList.lifecycleRootProof(second, secondStats));
                assertEquals(1, secondStats.fullSnapshotBuilds,
                        "the second target promotes the drain to one shared snapshot");
                assertTrue(secondStats.fullSnapshotGraphNodesVisited > 0);

                MortalList.LifecycleRootQueryStats thirdStats =
                        new MortalList.LifecycleRootQueryStats();
                assertEquals(MortalList.LifecycleRootProof.FULL_ROOT,
                        MortalList.lifecycleRootProof(third, thirdStats));
                assertEquals(0, thirdStats.fullSnapshotBuilds,
                        "later targets reuse the snapshot instead of walking again");
                assertEquals(0, thirdStats.targetedWalkQueries);

                MortalList.invalidateLiveRootSnapshot();
                MortalList.LifecycleRootQueryStats invalidatedStats =
                        new MortalList.LifecycleRootQueryStats();
                assertEquals(MortalList.LifecycleRootProof.FULL_ROOT,
                        MortalList.lifecycleRootProof(third, invalidatedStats));
                assertEquals(1, invalidatedStats.targetedWalkQueries,
                        "root mutation invalidates the cached drain snapshot");
                assertEquals(0, invalidatedStats.fullSnapshotBuilds);
            } finally {
                MyVarCleanupStack.unregister(third);
                MyVarCleanupStack.unregister(second);
                MyVarCleanupStack.unregister(first);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    @Test
    void liveWeakReferentsSkipCycleGraphQueries() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar weak = referent.createReference();
            WeakRefRegistry.weaken(weak);
            Set<RuntimeBase> live = Collections.newSetFromMap(new IdentityHashMap<>());
            live.add(referent);
            ReachabilityWalker.StrongCycleQueryStats stats =
                    new ReachabilityWalker.StrongCycleQueryStats();
            try {
                ReachabilityWalker.collectStrongCycleProtected(live, stats);
                assertTrue(stats.weakReferentsExamined > 0);
                assertTrue(stats.liveReferentsSkipped > 0);
                assertEquals(0, stats.graphBuilds,
                        "live referents are retained by the ordinary root walk");
                assertEquals(0, stats.strongGraphNodesExpanded);
            } finally {
                WeakRefRegistry.unweaken(weak);
            }
        }
    }

    @Test
    void unreachableStrongCycleStillGetsCycleProtection() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.elements.put("self", referent.createReference());
            RuntimeScalar weak = referent.createReference();
            WeakRefRegistry.weaken(weak);
            Set<RuntimeBase> live = Collections.newSetFromMap(new IdentityHashMap<>());
            ReachabilityWalker.StrongCycleQueryStats stats =
                    new ReachabilityWalker.StrongCycleQueryStats();
            try {
                Set<RuntimeBase> protectedSet =
                        ReachabilityWalker.collectStrongCycleProtected(live, stats);
                assertTrue(protectedSet.contains(referent));
                assertEquals(1, stats.graphBuilds);
                assertEquals(1, stats.strongGraphNodesExpanded);
                assertEquals(1, stats.cyclicNodesFound);
                assertTrue(stats.protectedGraphNodesVisited > 0);
            } finally {
                WeakRefRegistry.unweaken(weak);
            }
        }
    }

    @Test
    void acyclicWeakReferentThatLeadsToStrongCycleGetsCycleProtection() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeHash cycle = new RuntimeHash();
            referent.elements.add(cycle.createReference());
            cycle.elements.put("self", cycle.createReference());
            RuntimeScalar weak = referent.createReference();
            WeakRefRegistry.weaken(weak);
            Set<RuntimeBase> live = Collections.newSetFromMap(new IdentityHashMap<>());
            try {
                Set<RuntimeBase> protectedSet =
                        ReachabilityWalker.collectStrongCycleProtected(live, null);
                assertTrue(protectedSet.contains(referent));
                assertTrue(protectedSet.contains(cycle));
            } finally {
                WeakRefRegistry.unweaken(weak);
            }
        }
    }

    @Test
    void sharedStrongGraphIsExpandedOnceForMultipleWeakReferents() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray first = new RuntimeArray();
            RuntimeArray second = new RuntimeArray();
            RuntimeArray shared = new RuntimeArray();
            RuntimeHash leaf = new RuntimeHash();
            first.elements.add(shared.createReference());
            second.elements.add(shared.createReference());
            shared.elements.add(leaf.createReference());
            RuntimeScalar firstWeak = first.createReference();
            RuntimeScalar secondWeak = second.createReference();
            WeakRefRegistry.weaken(firstWeak);
            WeakRefRegistry.weaken(secondWeak);
            Set<RuntimeBase> live = Collections.newSetFromMap(new IdentityHashMap<>());
            ReachabilityWalker.StrongCycleQueryStats stats =
                    new ReachabilityWalker.StrongCycleQueryStats();
            try {
                Set<RuntimeBase> protectedSet =
                        ReachabilityWalker.collectStrongCycleProtected(live, stats);
                assertTrue(protectedSet.isEmpty());
                assertEquals(1, stats.graphBuilds);
                assertEquals(4, stats.strongGraphNodesExpanded,
                        "the shared child graph must be indexed only once");
                assertEquals(0, stats.cyclicNodesFound);
            } finally {
                WeakRefRegistry.unweaken(secondWeak);
                WeakRefRegistry.unweaken(firstWeak);
            }
        }
    }

    @Test
    void targetSpecificWalkStopsAtTheFirstContainerForAnEarlyTarget() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeArray global = new RuntimeArray();
            global.elements.add(target.createReference());
            for (int i = 0; i < 500; i++) {
                global.elements.add(new RuntimeHash().createReference());
            }
            GlobalVariable.globalArrays.put(
                    "ReachabilityQueryCostTest::global", global);
            int[] visitedNodes = {0};
            try {
                assertTrue(ReachabilityWalker.isReachableFromRoots(
                        target, false, visitedNodes));
                assertEquals(1, visitedNodes[0],
                        "the walk should return after inspecting the root array");
            } finally {
                GlobalVariable.globalArrays.remove(
                        "ReachabilityQueryCostTest::global");
            }
        }
    }
}
