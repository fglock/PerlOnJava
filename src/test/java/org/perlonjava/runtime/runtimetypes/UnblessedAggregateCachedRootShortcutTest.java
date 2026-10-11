package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class UnblessedAggregateCachedRootShortcutTest {
    @Test
    void rootedAggregateReusesAnExternalSnapshotBeforeBuildingFullRoots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            String globalName =
                    "UnblessedAggregateCachedRootShortcutTest::global";
            GlobalVariable.globalArrays.put(globalName, global);

            RuntimeHash blessedSeed = new RuntimeHash();
            blessedSeed.blessId = NameNormalizer.getBlessId(
                    "UnblessedAggregateCachedRootShortcutTest::Seed");
            RuntimeScalar blessedStrong = blessedSeed.createAnonymousReference();
            RuntimeScalar blessedWeak = blessedSeed.createAnonymousReference();
            global.elements.add(blessedStrong);
            WeakRefRegistry.weaken(blessedWeak);
            blessedSeed.refCount = 1;

            RuntimeArray aggregate = new RuntimeArray();
            RuntimeScalar aggregateStrong = aggregate.createAnonymousReference();
            RuntimeScalar aggregateWeak = aggregate.createAnonymousReference();
            global.elements.add(aggregateStrong);
            WeakRefRegistry.weaken(aggregateWeak);
            aggregate.refCount = 1;

            try {
                MortalList.pushMark();
                MortalList.deferDecrement(blessedSeed);
                MortalList.deferDecrement(aggregate);

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.popAndFlush();

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(2, stats.deferredBasesProcessed);
                    assertEquals(1, stats.externalRootSnapshotsBuilt,
                            "the blessed seed builds one package-root snapshot");
                    assertEquals(0, stats.snapshotsBuilt,
                            "the rooted aggregate reuses the existing external proof");
                    assertTrue(stats.externalRootSnapshotNodesVisited > 0);
                    assertEquals(1, blessedSeed.refCount);
                    assertEquals(1, aggregate.refCount);
                }
            } finally {
                WeakRefRegistry.unweaken(blessedWeak);
                WeakRefRegistry.unweaken(aggregateWeak);
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    @Test
    void positiveAggregateProofSurvivesRootRemovalUntilDrainEnds() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            String globalName =
                    "UnblessedAggregateCachedRootShortcutTest::removedRoot";
            GlobalVariable.globalArrays.put(globalName, global);
            RuntimeArray aggregate = new RuntimeArray();
            RuntimeScalar strong = aggregate.createAnonymousReference();
            RuntimeScalar weak = aggregate.createAnonymousReference();
            global.elements.add(strong);
            WeakRefRegistry.weaken(weak);
            aggregate.refCount = 1;

            LifecycleRuntimeState state = runtime.lifecycleState;
            try {
                state.flushing = true;
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    assertTrue(MortalList.isUnblessedAggregateReachableDuringDrain(aggregate));
                    assertEquals(1, measurement.stats().snapshotsBuilt);

                    global.elements.remove(strong);
                    MortalList.invalidateExternalRootSnapshot();

                    assertTrue(MortalList.isUnblessedAggregateReachableDuringDrain(aggregate),
                            "a positive proof is retained until this cleanup drain ends");
                    assertEquals(1, measurement.stats().snapshotsBuilt,
                            "root removal must not rebuild the full graph for this aggregate");
                }

                state.flushing = false;
                MortalList.pushMark();
                MortalList.deferDecrement(aggregate);
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.popAndFlush();
                    assertEquals(RuntimeScalarType.UNDEF, weak.type,
                            "the next drain must discard the previous drain's positive proof");
                    assertEquals(null, weak.value,
                            "the weak observer must be cleared after the aggregate becomes unreachable");
                    assertEquals(1, measurement.stats().snapshotsBuilt,
                            "the new drain must build a current root snapshot");
                }
            } finally {
                state.flushing = false;
                state.unblessedAggregateDrainRoots.clear();
                state.unblessedAggregateDrainSnapshotIncomplete = false;
                WeakRefRegistry.unweaken(weak);
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }
}
