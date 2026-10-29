package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class UnblessedScalarCellOwnerSlotRepairTest {
    @Test
    void unattachedScalarWrapperDoesNotCountAsCurrentPerlCellOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeScalar unrootedOwner = new RuntimeScalar();
            unrootedOwner.set(target.createAnonymousReference());
            try {
                assertFalse(unrootedOwner.isCurrentCountedScalarOwner(
                                target, unrootedOwner.countedOwnerSlot()),
                        "a Java-reachable wrapper without Perl storage is not a current owner");
            } finally {
                unrootedOwner.set(new RuntimeScalar());
            }
        }
    }

    @Test
    void captureCountWithoutLiveCodeOwnerDoesNotKeepScalarCellCurrent() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeScalar capturedOwner = new RuntimeScalar();
            capturedOwner.set(target.createAnonymousReference());
            capturedOwner.createReference();
            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            closure.capturedScalars = new RuntimeScalar[]{capturedOwner};
            closure.capturedScalarOwnerSlots = new PerlOwnerSlot[]{
                    capturedOwner.retainClosureCaptureSlot(closure)
            };
            try {
                assertFalse(closure.hasPerlOwnerForCapture());
                assertFalse(capturedOwner.isCurrentCountedScalarOwner(
                                target, capturedOwner.countedOwnerSlot()),
                        "capture metadata alone does not prove a live closure owner");
            } finally {
                closure.releaseCaptures();
                capturedOwner.set(new RuntimeScalar());
            }
        }
    }

    @Test
    void codeSlotCountsAsCurrentScalarBackedOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeScalar codeOwner = new RuntimeScalar(code);
            codeOwner.retainCodeSlotBinding();
            try {
                assertEquals(PerlOwnerSlot.Kind.CODE_SLOT, codeOwner.countedOwnerSlot().kind());
                assertTrue(codeOwner.isCurrentCountedScalarOwner(code,
                                codeOwner.countedOwnerSlot()));
            } finally {
                codeOwner.releaseCodeSlotBinding();
            }
        }
    }

    @Test
    void scalarOverwriteRestoresFromSurvivingScalarCellWithoutRootWalk() {
        exerciseScalarCellRepair(false);
    }

    @Test
    void deferredReleaseRestoresFromSurvivingScalarCellWithoutRootWalk() {
        exerciseScalarCellRepair(true);
    }

    @Test
    void scalarAndAggregateOwnersAreCountedTogetherAfterUndercount() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            MortalList.setActive(true);
            RuntimeHash target = new RuntimeHash();
            RuntimeScalar firstOwner = new RuntimeScalar();
            RuntimeScalar survivingScalarOwner = new RuntimeScalar();
            RuntimeArray aggregateOwner = new RuntimeArray();
            RuntimeScalar weakObserver = target.createAnonymousReference();
            int mark = MyVarCleanupStack.pushMark();
            try {
                MyVarCleanupStack.register(firstOwner);
                MyVarCleanupStack.register(survivingScalarOwner);
                firstOwner.set(target.createAnonymousReference());
                survivingScalarOwner.set(target.createAnonymousReference());
                aggregateOwner.push(target.createAnonymousReference());
                WeakRefRegistry.weaken(weakObserver);

                assertEquals(3, target.refCount);
                target.refCount = 1; // Model one undercount across mixed owner families.

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    firstOwner.set(new RuntimeScalar());

                    assertEquals(2, target.refCount);
                    assertTrue(WeakRefRegistry.isweak(weakObserver));
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                survivingScalarOwner.set(new RuntimeScalar());
                RuntimeArray.pop(aggregateOwner);
                MortalList.flush();
                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "the last scalar and aggregate owners clear the weak observer");
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                firstOwner.set(new RuntimeScalar());
                survivingScalarOwner.set(new RuntimeScalar());
                while (!aggregateOwner.elements.isEmpty()) RuntimeArray.pop(aggregateOwner);
                MortalList.flush();
                MyVarCleanupStack.unregister(firstOwner);
                MyVarCleanupStack.unregister(survivingScalarOwner);
                MyVarCleanupStack.popMark(mark);
                MortalList.setActive(previousMortalListState);
            }
        }
    }

    private void exerciseScalarCellRepair(boolean deferRelease) {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            MortalList.setActive(true);
            RuntimeHash target = new RuntimeHash();
            RuntimeScalar firstOwner = new RuntimeScalar();
            RuntimeScalar survivingOwner = new RuntimeScalar();
            RuntimeScalar weakObserver = target.createAnonymousReference();
            int mark = MyVarCleanupStack.pushMark();
            try {
                MyVarCleanupStack.register(firstOwner);
                MyVarCleanupStack.register(survivingOwner);
                firstOwner.set(target.createAnonymousReference());
                survivingOwner.set(target.createAnonymousReference());
                WeakRefRegistry.weaken(weakObserver);

                assertEquals(2, target.refCount);
                assertEquals(2, target.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
                target.refCount = 1; // Model a transient legacy-count undercount.

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    if (deferRelease) {
                        MortalList.pushMark();
                        MortalList.deferDecrement(target);
                        MortalList.popAndFlush();
                    } else {
                        firstOwner.set(new RuntimeScalar());
                    }

                    assertEquals(deferRelease ? 2 : 1, target.refCount);
                    assertTrue(WeakRefRegistry.isweak(weakObserver));
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                firstOwner.set(new RuntimeScalar());
                survivingOwner.set(new RuntimeScalar());
                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "the final scalar-cell owner release clears the weak observer");
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                firstOwner.set(new RuntimeScalar());
                survivingOwner.set(new RuntimeScalar());
                MyVarCleanupStack.unregister(firstOwner);
                MyVarCleanupStack.unregister(survivingOwner);
                MyVarCleanupStack.popMark(mark);
                MortalList.setActive(previousMortalListState);
            }
        }
    }
}
