package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class UnblessedAggregateCachedRootShortcutTest {
    @Test
    void rootedAggregatesReleaseTemporaryOwnersWithoutRootSnapshots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            String globalName =
                    "UnblessedAggregateCachedRootShortcutTest::global";
            GlobalVariable.globalArrays.put(globalName, global);

            RuntimeHash blessedSeed = new RuntimeHash();
            blessedSeed.blessId = NameNormalizer.getBlessId(
                    "UnblessedAggregateCachedRootShortcutTest::Seed");
            RuntimeArray aggregate = new RuntimeArray();
            RuntimeScalar seedOwner = CountedOwnerFixture.reference(
                    blessedSeed, PerlOwnerSlot.Kind.ARRAY_ELEMENT);
            RuntimeScalar aggregateOwner = CountedOwnerFixture.reference(
                    aggregate, PerlOwnerSlot.Kind.ARRAY_ELEMENT);
            global.elements.add(seedOwner);
            global.elements.add(aggregateOwner);
            RuntimeScalar seedWeak = blessedSeed.createAnonymousReference();
            RuntimeScalar aggregateWeak = aggregate.createAnonymousReference();
            WeakRefRegistry.weaken(seedWeak);
            WeakRefRegistry.weaken(aggregateWeak);

            try {
                MortalList.pushMark();
                CountedOwnerFixture.queueRelease(
                        CountedOwnerFixture.temporaryReference(blessedSeed));
                CountedOwnerFixture.queueRelease(
                        CountedOwnerFixture.temporaryReference(aggregate));

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.popAndFlush();

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(2, stats.deferredBasesProcessed);
                    assertEquals(0, stats.rootQueries);
                    assertEquals(0, stats.externalRootSnapshotsBuilt);
                    assertEquals(0, stats.snapshotsBuilt);
                    assertEquals(1, blessedSeed.refCount);
                    assertEquals(1, aggregate.refCount);
                    assertTrue(WeakRefRegistry.isweak(seedWeak));
                    assertTrue(WeakRefRegistry.isweak(aggregateWeak));
                }
            } finally {
                WeakRefRegistry.unweaken(seedWeak);
                WeakRefRegistry.unweaken(aggregateWeak);
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }

    @Test
    void finalExactOwnerReleaseClearsWeakObserverWithoutDrainProof() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray aggregate = new RuntimeArray();
            RuntimeScalar temporaryOwner =
                    CountedOwnerFixture.temporaryReference(aggregate);
            RuntimeScalar weak = aggregate.createAnonymousReference();
            WeakRefRegistry.weaken(weak);

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                CountedOwnerFixture.queueRelease(
                        temporaryOwner);
                MortalList.flush();

                assertEquals(RuntimeScalarType.UNDEF, weak.type);
                assertEquals(null, weak.value);
                assertEquals(1, measurement.stats().deferredBasesProcessed);
                assertEquals(0, measurement.stats().rootQueries);
                assertEquals(0, measurement.stats().externalRootSnapshotsBuilt);
            }
        }
    }


}
