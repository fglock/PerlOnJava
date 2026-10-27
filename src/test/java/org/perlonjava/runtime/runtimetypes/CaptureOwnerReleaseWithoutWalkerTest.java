package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
class CaptureOwnerReleaseWithoutWalkerTest {
    @Test
    void explicitCodeOwnerLedgerControlsScopeExitedCaptureRelease() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar pad = referent.createAnonymousReference();
            pad.scopeExited = true;

            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            closure.refCount = 1;
            closure.capturedScalars = new RuntimeScalar[] {pad};
            closure.capturedScalarOwnerSlots = new PerlOwnerSlot[] {
                    pad.retainClosureCaptureSlot(closure)
            };

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                ReachabilityWalker.releaseUnreachableScopeExitedCaptureOwners(
                        List.of(referent), null);

                assertNotNull(closure.capturedScalars,
                        "a CODE owner with a Perl owner keeps its capture alive");
                assertEquals(0, measurement.stats().rootQueries,
                        "explicit CODE ownership requires no reachability walk");
            }

            closure.refCount = 0;
            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                ReachabilityWalker.releaseUnreachableScopeExitedCaptureOwners(
                        List.of(referent), null);

                assertNull(closure.capturedScalars,
                        "an ownerless CODE releases its captures at scope exit");
                assertEquals(0, referent.semanticCaptureOwnerCount());
                assertEquals(0, measurement.stats().rootQueries,
                        "retiring an explicit capture owner requires no reachability walk");
            }
        }
    }
}
