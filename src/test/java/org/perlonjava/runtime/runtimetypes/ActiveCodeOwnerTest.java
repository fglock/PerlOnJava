package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class ActiveCodeOwnerTest {
    @Test
    void borrowedCodeIsNotFinalizedWithoutACountedOwnerRelease() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar pad = new RuntimeScalar(42);
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            code.refCount = 0;
            code.capturedScalars = new RuntimeScalar[]{pad};
            code.capturedScalarOwnerSlots = new PerlOwnerSlot[]{pad.retainClosureCaptureSlot()};
            RuntimeCode.pushActiveCode(code);
            RuntimeCode.popActiveCode(code);
            assertNotNull(code.capturedScalars);
            assertEquals(1, pad.captureCount);
            assertEquals(0, code.nativeOwnerSlotCount());
            code.releaseCaptures();
            assertEquals(0, pad.captureCount);
        }
    }

    @Test
    void recursiveFramesHaveDistinctNativeOwnersAndReleaseAtTheLastReturn() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            code.refCount = 0;
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(new RuntimeScalar(code));
            RuntimeScalar weak = new RuntimeScalar();
            weak.set(owner);
            WeakRefRegistry.weaken(weak);
            RuntimeCode.pushActiveCode(code);
            RuntimeCode.pushActiveCode(code);
            var identities = new HashSet<Long>();
            for (PerlOwnerSlot slot : code.ownerSlotSnapshot()) {
                if (slot.kind() == PerlOwnerSlot.Kind.ACTIVE_CODE) {
                    assertTrue(slot.isNativeOwner());
                    identities.add(slot.identity());
                }
            }
            assertEquals(2, identities.size());
            assertEquals(2, code.nativeOwnerSlotCount());
            assertEquals(0, runtime.positiveOwnerSlotReferentCount(code));
            owner.set(new RuntimeScalar());
            assertEquals(0, code.refCount);
            assertEquals(RuntimeScalarType.CODE, weak.type);
            RuntimeCode.popActiveCode(code);
            assertEquals(1, code.nativeOwnerSlotCount());
            assertEquals(RuntimeScalarType.CODE, weak.type);
            try (MortalList.ReachabilityQueryMeasurement measurement = MortalList.measureReachabilityQueries()) {
                RuntimeCode.popActiveCode(code);
                assertEquals(0, code.nativeOwnerSlotCount());
                assertEquals(RuntimeScalarType.UNDEF, weak.type);
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, code.perlLifecycleState());
                assertEquals(0, measurement.stats().rootQueries);
                assertEquals(0, measurement.stats().snapshotsBuilt);
                assertEquals(0, measurement.stats().scalarReachabilityQueries);
            }
        }
    }

    @Test
    void activeFrameProtectsCapturesUntilTheCallerDrainsTheirRetiredOwners() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash guard = new RuntimeHash();
            RuntimeScalar pad = new RuntimeScalar();
            pad.set(guard.createAnonymousReference());
            RuntimeScalar weak = new RuntimeScalar();
            weak.set(pad);
            WeakRefRegistry.weaken(weak);
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            code.refCount = 0;
            code.capturedScalars = new RuntimeScalar[]{pad};
            code.capturedScalarOwnerSlots = new PerlOwnerSlot[]{pad.retainClosureCaptureSlot()};
            RuntimeScalar.scopeExitCleanup(pad);
            RuntimeCode.pushActiveCode(code);
            code.releaseCaptures();
            assertNotNull(code.capturedScalars);
            assertEquals(1, pad.captureCount);
            assertEquals(RuntimeScalarType.HASHREFERENCE, weak.type);
            RuntimeCode.popActiveCode(code);
            MortalList.flushAboveMark();
            assertNull(code.capturedScalars);
            assertEquals(0, pad.captureCount);
            assertEquals(RuntimeScalarType.UNDEF, weak.type);
        }
    }

    @Test
    void removingAnOuterFrameRetiresOneIdentityAndPreservesOtherRecursiveFrames() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeCode other = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeCode.pushActiveCode(code);
            RuntimeCode.pushActiveCode(code);
            RuntimeCode.pushActiveCode(other);
            RuntimeCode.popActiveCode(code);
            assertEquals(1, code.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_CODE));
            assertEquals(1, other.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_CODE));
            RuntimeCode.popActiveCode(other);
            RuntimeCode.popActiveCode(code);
            assertFalse(code.hasActiveCodeOwner());
            assertFalse(other.hasActiveCodeOwner());
            assertTrue(runtime.executionState().activeCodeStack.isEmpty());
        }
    }
    @Test
    void inactiveClosureCaptureIsNotRetiredByScopeBoundaryReachabilityCorrection() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash guard = new RuntimeHash();
            guard.blessId = NameNormalizer.getBlessId("Issue1649::CapturedScopeGuard");
            RuntimeScalar pad = new RuntimeScalar();
            pad.set(guard.createAnonymousReference());
            RuntimeScalar weak = new RuntimeScalar();
            weak.set(pad);
            WeakRefRegistry.weaken(weak);
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            code.refCount = 0;
            code.capturedScalars = new RuntimeScalar[]{pad};
            code.capturedScalarOwnerSlots = new PerlOwnerSlot[]{pad.retainClosureCaptureSlot()};
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(new RuntimeScalar(code));
            RuntimeScalar.scopeExitCleanup(pad);
            MortalList.flushAboveMark();
            assertEquals(1, pad.captureCount);
            assertEquals(RuntimeScalarType.HASHREFERENCE, weak.type);
            assertTrue(runtime.lifecycleState.deferredScopeExitCaptureCandidates.isEmpty());
            owner.set(RuntimeScalarCache.scalarUndef);
            assertEquals(0, pad.captureCount);
            assertEquals(RuntimeScalarType.UNDEF, weak.type);
        }
    }

}
