package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TargetedWeakReleaseOwnerSlotFastPathTest {
    @Test
    void activeElementOwnerAvoidsTargetedRootWalk() {
        Assumptions.assumeTrue(ReachabilityWalker.correctiveSweepsEnabled(),
                "this compatibility path only runs when corrective sweeps are enabled");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray owner = new RuntimeArray();
            RuntimeArray target = new RuntimeArray();
            owner.addClonedElement(target.createAnonymousReference());
            RuntimeScalar weak = target.createAnonymousReference();
            WeakRefRegistry.weaken(weak);

            try {
                assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    ReachabilityWalker.ReleasedWeakSweepResult result =
                            ReachabilityWalker.sweepReleasedWeakReferents(
                                    Collections.singleton(target));

                    assertNull(result.live(),
                            "a slot owner needs no reachability snapshot");
                    assertTrue(result.countedStrongOwners().contains(target));
                    assertTrue(WeakRefRegistry.isweak(weak),
                            "the active element slot keeps the target's weak observer intact");
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }
            } finally {
                WeakRefRegistry.unweaken(weak);
            }
        }
    }
}
