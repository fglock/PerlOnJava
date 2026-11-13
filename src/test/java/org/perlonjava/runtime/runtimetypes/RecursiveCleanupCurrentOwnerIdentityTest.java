package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RecursiveCleanupCurrentOwnerIdentityTest {
    @Test
    void closureOwnedAggregateSkipsExternalRootSnapshotAndKeepsNestedWeakValue() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "this lifecycle regression requires corrective sweeps to be disabled");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);

            RuntimeArray unrelatedPackageRoot = new RuntimeArray();
            for (int i = 0; i < 128; i++) {
                RuntimeArray.push(unrelatedPackageRoot,
                        new RuntimeHash().createAnonymousReference());
            }
            String globalName = "RecursiveCleanupCurrentOwnerIdentityTest::global";
            GlobalVariable.globalArrays.put(globalName, unrelatedPackageRoot);

            RuntimeHash nested = new RuntimeHash();
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar weak = referent.createAnonymousReference();
            RuntimeScalar strong = referent.createAnonymousReference();
            WeakRefRegistry.weaken(weak);
            nested.put("referent", strong);

            RuntimeHash captured = new RuntimeHash();
            captured.put("nested", nested.createAnonymousReference());
            PerlOwnerSlot capture = captured.retainClosureCaptureOwner();
            RuntimeScalar removedOwner = captured.createAnonymousReference();
            RuntimeScalar.incrementRefCountForContainerStore(removedOwner);
            assertEquals(1, captured.refCount);

            // Model a legacy count without a current counted slot. The live
            // closure-pad identity is still authoritative for the aggregate.
            captured.refCount++;

            try {
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.deferDestroyForContainerClear(List.of(removedOwner));
                    MortalList.flush();

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(0, stats.rootQueries,
                            "the closure owner identity avoids a package-root query");
                    assertEquals(0, stats.externalRootSnapshotsBuilt,
                            "unrelated package roots do not add lifecycle cleanup work");
                    assertTrue(WeakRefRegistry.isweak(weak));
                    assertTrue(weak.value == referent,
                            "the captured aggregate keeps its nested referent alive");
                }
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
                WeakRefRegistry.unweaken(weak);
                captured.releaseClosureCaptureOwner(capture);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }
}
