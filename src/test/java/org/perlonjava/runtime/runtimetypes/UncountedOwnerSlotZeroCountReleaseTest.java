package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("unit")
class UncountedOwnerSlotZeroCountReleaseTest {
    @Test
    void scalarOverwriteDefersFinalCleanupUntilUncountedOwnerLeaves() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            Fixture fixture = fixture();
            assertFinalOwnerRelease(fixture,
                    () -> fixture.countedOwner.set(new RuntimeScalar()));
        }
    }

    @Test
    void deferredDecrementDefersFinalCleanupUntilUncountedOwnerLeaves() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            Fixture fixture = fixture();
            assertFinalOwnerRelease(fixture, () -> {
                MortalList.deferDecrementIfTracked(fixture.countedOwner);
                MortalList.flushAboveMark();
            });
        }
    }

    @Test
    void tailCallReleaseDefersFinalCleanupUntilUncountedOwnerLeaves() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            Fixture fixture = fixture();
            RuntimeArray tailArguments = new RuntimeArray(1);
            tailArguments.elements.add(fixture.countedOwner);
            tailArguments.elementsOwned = true;
            assertFinalOwnerRelease(fixture,
                    () -> MortalList.releaseTailCallArgs(tailArguments));
        }
    }

    @Test
    void finalRawOwnerSlotReleaseFinishesDeferredDestruction() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            String className = "UncountedOwnerSlotZeroCountReleaseTest::RawTarget";
            String destroyFqn = className + "::DESTROY";
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId(className);
            target.refCount = -1;

            AtomicInteger destroyCalls = new AtomicInteger();
            RuntimeCode destroy = new RuntimeCode((args, context) -> {
                destroyCalls.incrementAndGet();
                return new RuntimeList();
            }, null);
            destroy.packageName = "UncountedOwnerSlotZeroCountReleaseTest";
            destroy.subName = "DESTROY";
            GlobalVariable.getGlobalCodeRef(destroyFqn).set(new RuntimeScalar(destroy));
            DestroyDispatch.registerIfDestroyable(target, target.blessId);

            RuntimeScalar countedOwner = new RuntimeScalar();
            countedOwner.set(target.createAnonymousReference());
            target.refCount = 1;
            PerlOwnerSlot rawOwner = new PerlOwnerSlot(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE);
            rawOwner.acquireUntrackedIdentity(target);
            try {
                target.deferFinalReleaseToUncountedOwnerSlots();
                assertEquals(WeakRefRegistry.WEAKLY_TRACKED, target.refCount);

                target.releaseActiveOwner(countedOwner);
                countedOwner.refCountOwned = false;
                assertEquals(0, destroyCalls.get(),
                        "the remaining raw owner slot keeps the referent alive");

                rawOwner.release();

                assertEquals(Integer.MIN_VALUE, target.refCount);
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        target.perlLifecycleState());
                assertEquals(1, destroyCalls.get(),
                        "the last owner identity dispatches destruction exactly once");
            } finally {
                if (rawOwner.isActive()) rawOwner.release();
                if (countedOwner.countedOwnerSlot() != null
                        && countedOwner.countedOwnerSlot().isActive()) {
                    target.releaseActiveOwner(countedOwner);
                }
                GlobalVariable.globalCodeRefs.remove(destroyFqn);
                DestroyDispatch.unregisterDestroyable(target);
            }
        }
    }

    private static Fixture fixture() {
        String className = "UncountedOwnerSlotZeroCountReleaseTest::Target";
        RuntimeHash target = new RuntimeHash();
        target.blessId = NameNormalizer.getBlessId(className);
        target.refCount = -1;

        AtomicInteger destroyCalls = new AtomicInteger();
        RuntimeCode destroy = new RuntimeCode((args, context) -> {
            destroyCalls.incrementAndGet();
            return new RuntimeList();
        }, null);
        destroy.packageName = "UncountedOwnerSlotZeroCountReleaseTest";
        destroy.subName = "DESTROY";
        String destroyFqn = className + "::DESTROY";
        GlobalVariable.getGlobalCodeRef(destroyFqn).set(new RuntimeScalar(destroy));

        RuntimeArray ownerContainer = new RuntimeArray();
        RuntimeScalar uncountedOwner = referenceTo(target);
        ownerContainer.elements.add(uncountedOwner);
        PerlOwnerSlot ownerSlot = uncountedOwner.untrackedReferenceOwnerSlot();
        assertNotNull(ownerSlot);
        assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

        RuntimeScalar countedOwner = new RuntimeScalar();
        countedOwner.set(target.createAnonymousReference());
        assertEquals(1, target.countedOwnerSlotCount());
        target.refCount = 1; // Model a transient legacy-count undercount.
        assertFalse(WeakRefRegistry.hasWeakRefsTo(target));
        return new Fixture(target, ownerContainer, uncountedOwner, ownerSlot, countedOwner,
                destroyCalls, destroyFqn);
    }

    private static void assertFinalOwnerRelease(Fixture fixture, Runnable countedRelease) {
        try {
            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                countedRelease.run();

                assertEquals(WeakRefRegistry.WEAKLY_TRACKED, fixture.target.refCount);
                assertEquals(RuntimeBase.PerlLifecycleState.LIVE,
                        fixture.target.perlLifecycleState());
                assertEquals(1,
                        fixture.target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                assertEquals(0, measurement.stats().rootQueries,
                        "the surviving owner slot replaces root-graph rescue");
                assertEquals(0, measurement.stats().snapshotsBuilt);
            }

            fixture.uncountedOwner.set(new RuntimeScalar());

            assertFalse(fixture.ownerSlot.isActive());
            assertEquals(0,
                    fixture.target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
            assertEquals(Integer.MIN_VALUE, fixture.target.refCount);
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                    fixture.target.perlLifecycleState());
            assertEquals(1, fixture.destroyCalls.get(),
                    "the final uncounted owner invokes DESTROY exactly once");
        } finally {
            if (fixture.ownerSlot.isActive()) {
                fixture.uncountedOwner.set(new RuntimeScalar());
            }
            fixture.countedOwner.set(new RuntimeScalar());
            GlobalVariable.globalCodeRefs.remove(fixture.destroyFqn);
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.REFERENCE;
        scalar.value = referent;
        return scalar;
    }

    private record Fixture(RuntimeHash target, RuntimeArray ownerContainer,
                           RuntimeScalar uncountedOwner, PerlOwnerSlot ownerSlot,
                           RuntimeScalar countedOwner, AtomicInteger destroyCalls,
                           String destroyFqn) {
    }
}
