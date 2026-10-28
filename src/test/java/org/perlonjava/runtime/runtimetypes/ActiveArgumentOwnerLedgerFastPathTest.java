package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ActiveArgumentOwnerLedgerFastPathTest {
    @Test
    void countedCallerCellAvoidsReferentScanAndStillReleasesAtCallEnd() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId("Issue1649::ActiveArgumentLedger");
            RuntimeScalar released = new RuntimeScalar();
            released.set(target.createAnonymousReference());
            RuntimeScalar callerCell = new RuntimeScalar();
            callerCell.set(target.createAnonymousReference());
            RuntimeScalar weak = target.createAnonymousReference();
            WeakRefRegistry.weaken(weak);
            RuntimeCode.CallArguments arguments = RuntimeCode.newCallArguments(1);
            arguments.add(callerCell);

            try {
                assertEquals(0, target.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE),
                        "a persistent caller cell remains an aliased borrow");
                target.refCount = 1; // Model a transient legacy-count underflow.
                MortalList.deferDecrementIfTracked(released);

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.flushAboveMark();

                    assertEquals(1, target.refCount);
                    assertEquals(1, target.countedOwnerSlotCount());
                    assertTrue(runtime.lifecycleState.deferredArgumentOwnerReleases.isEmpty(),
                            "the owner ledger avoids scanning the active argument frame");
                    assertTrue(WeakRefRegistry.isweak(weak));
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                arguments.finish();
                callerCell.set(new RuntimeScalar());
                assertEquals(RuntimeScalarType.UNDEF, weak.type,
                        "clearing the caller's final slot clears the weak observer");
                assertEquals(0, target.countedOwnerSlotCount());
            } finally {
                arguments.finish();
                if (WeakRefRegistry.isweak(weak)) WeakRefRegistry.unweaken(weak);
            }
        }
    }
}
