package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CapturedScalarCellOwnerFastPathTest {
    @Test
    void activeClosureCaptureRepairsUnblessedScalarCellOwnerWithoutRootWalk() {
        exerciseCaptureOwnerRepair(false);
    }

    @Test
    void deferredCleanupUsesActiveClosureCaptureWithoutRootWalk() {
        exerciseCaptureOwnerRepair(true);
    }

    private void exerciseCaptureOwnerRepair(boolean deferScalarRelease) {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            MortalList.setActive(deferScalarRelease);
            try {
                RuntimeHash target = new RuntimeHash();
                RuntimeScalar seedOwner = new RuntimeScalar();
                RuntimeScalar weakObserver = target.createAnonymousReference();
                RuntimeScalar capturedOwner = new RuntimeScalar();
                RuntimeScalar transientOwner = new RuntimeScalar();
                RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
                int mark = MyVarCleanupStack.pushMark();
                MyVarCleanupStack.register(seedOwner);
                MyVarCleanupStack.register(capturedOwner);
                MyVarCleanupStack.register(transientOwner);

                try {
                    seedOwner.set(target.createAnonymousReference());
                    WeakRefRegistry.weaken(weakObserver);
                    capturedOwner.set(target.createAnonymousReference());
                    transientOwner.set(target.createAnonymousReference());
                    closure.capturedScalars = new RuntimeScalar[]{capturedOwner};
                    closure.capturedScalarOwnerSlots = new PerlOwnerSlot[]{
                            capturedOwner.retainClosureCaptureSlot(closure)
                    };
                    RuntimeCode.pushActiveCode(closure);
                    RuntimeScalar.scopeExitCleanup(capturedOwner);
                    MyVarCleanupStack.unregister(capturedOwner);
                    seedOwner.set(new RuntimeScalar());

                    assertFalse(target.localBindingExists);
                    assertEquals(PerlOwnerSlot.Kind.SCALAR_CELL,
                            capturedOwner.countedOwnerSlot().kind());
                    assertTrue(capturedOwner.refCountOwned,
                            "the captured scalar cell still owns its reference count");
                    assertTrue(capturedOwner.countedOwnerSlot().isActive(),
                            "the captured scalar cell's owner identity is active");
                    assertSame(target, capturedOwner.countedOwnerSlot().referent());
                    assertSame(capturedOwner, capturedOwner.countedOwnerSlot().countedScalarOwner());
                    assertEquals(1, capturedOwner.captureCount);
                    assertEquals(1, capturedOwner.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE));
                    assertTrue(closure.hasPerlOwnerForCapture(),
                            "the closure remains a Perl owner of the captured scalar cell");
                    assertTrue(capturedOwner.isCurrentCapturedScalarCellOwner(
                                    target, capturedOwner.countedOwnerSlot()),
                            "the exact scalar-cell and closure-capture identities prove ownership");

                    // Model a selective-count undercount while preserving the
                    // active closure's scalar-cell owner identity.
                    target.refCount = 1;
                    try (MortalList.ReachabilityQueryMeasurement measurement =
                                 MortalList.measureReachabilityQueries()) {
                        transientOwner.set(new RuntimeScalar());

                        assertEquals(1, target.refCount);
                        assertTrue(WeakRefRegistry.isweak(weakObserver));
                        assertEquals(0, measurement.stats().scalarReachabilityQueries,
                                "the active capture identity is the liveness proof");
                        assertEquals(0, measurement.stats().scalarReachabilityRootWalks);
                        assertEquals(0, measurement.stats().rootQueries);
                    }

                    MortalList.setActive(true);
                    RuntimeCode.popActiveCode(closure);
                    closure.releaseCaptures();
                    MortalList.flush();
                    assertFalse(WeakRefRegistry.isweak(weakObserver),
                            "releasing the captured scalar cell clears the final weak observer");
                    assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
                } finally {
                    if (RuntimeCode.isActiveCode(closure)) RuntimeCode.popActiveCode(closure);
                    if (closure.capturedScalars != null) closure.releaseCaptures();
                    if (WeakRefRegistry.isweak(weakObserver)) {
                        WeakRefRegistry.unweaken(weakObserver);
                    }
                    RuntimeScalar.scopeExitCleanup(seedOwner);
                    RuntimeScalar.scopeExitCleanup(capturedOwner);
                    RuntimeScalar.scopeExitCleanup(transientOwner);
                    MyVarCleanupStack.unregister(seedOwner);
                    MyVarCleanupStack.unregister(capturedOwner);
                    MyVarCleanupStack.unregister(transientOwner);
                    MyVarCleanupStack.popMark(mark);
                }
            } finally {
                MortalList.setActive(previousMortalListState);
            }
        }
    }
}
