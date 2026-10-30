package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class CorrectiveSweepDefaultPolicyTest {
    @Test
    void defaultRuntimeDoesNotQueueOrExecuteCorrectiveSweeps() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "legacy sweep comparison was explicitly enabled");
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.blessId = NameNormalizer.getBlessId("Issue1649::SweepPolicy");
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(referent.createAnonymousReference());
            RuntimeScalar weak = new RuntimeScalar();
            weak.set(owner);
            WeakRefRegistry.weaken(weak);
            try (MortalList.ReachabilityQueryMeasurement measurement = MortalList.measureReachabilityQueries()) {
                owner.scopeExited = true;
                MortalList.noteDeferredScopeExitCaptureCandidate(owner);
                MortalList.addDeferredCapture(owner);
                assertTrue(runtime.lifecycleState.deferredScopeExitCaptureCandidates.isEmpty());
                MortalList.flushDeferredCapturesBeforeEnd();
                assertEquals(1, runtime.lifecycleState.deferredCaptures.size());
                MortalList.requestImmediateWeakSweep();
                MortalList.requestTargetedWeakSweep(referent);
                assertFalse(MortalList.requestWeakSweepsForDestroyedContainer(referent));
                DestroyDispatch.requestSweepAfterOuterDestroy();
                assertFalse(runtime.lifecycleState.immediateWeakSweepRequested);
                assertTrue(runtime.lifecycleState.targetedWeakSweepReferents.isEmpty());
                assertFalse(runtime.lifecycleState.sweepPendingAfterOuterDestroy);
                assertEquals(0, ReachabilityWalker.sweepWeakRefs());
                assertEquals(0, ReachabilityWalker.sweepWeakRefs(true));
                assertEquals(RuntimeScalarType.HASHREFERENCE, weak.type);
                assertEquals(0, measurement.stats().rootQueries);
                assertEquals(0, measurement.stats().snapshotsBuilt);
            }
        }
    }
}
