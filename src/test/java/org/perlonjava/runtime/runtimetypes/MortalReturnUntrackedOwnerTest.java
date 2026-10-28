package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class MortalReturnUntrackedOwnerTest {
    @Test
    void untrackedReturnOwnerLivesThroughNestedMortalBoundaryAndReleasesOnce() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "return-slot ownership must release without corrective sweeps");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar target = new RuntimeScalar("return target");
            RuntimeScalar source = referenceTo(target);

            MortalList.pushMark();
            RuntimeScalar returned = RuntimeScalar.copyToMortalReturnSlot(source);
            PerlOwnerSlot returnOwner = returned.untrackedReferenceOwnerSlot();
            assertNotNull(returnOwner);
            assertSame(target, returnOwner.referent());
            assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
            assertEquals(-1, target.refCount,
                    "return ownership must not promote an untracked referent");

            MortalList.pushMark();
            MortalList.popAndFlush();
            assertTrue(returnOwner.isActive(),
                    "an inner frame must not release its caller's return value");

            MortalList.popAndFlush();
            assertFalse(returnOwner.isActive());
            assertEquals(0, target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
            assertEquals(-1, target.refCount,
                    "releasing a return identity must leave the legacy count unchanged");
        }
    }

    @Test
    void weaklyTrackedReturnOwnerClearsObserverAfterLastScalarCellLeaves() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "return-slot ownership must release without corrective sweeps");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar target = new RuntimeScalar("weakly tracked return target");
            RuntimeScalar source = new RuntimeScalar();
            RuntimeScalar weakObserver = referenceTo(target);
            int lexicalMark = MyVarCleanupStack.pushMark();
            MyVarCleanupStack.register(source);
            source.set(referenceTo(target));
            WeakRefRegistry.weaken(weakObserver);
            assertEquals(WeakRefRegistry.WEAKLY_TRACKED, target.refCount);

            try {
                MortalList.pushMark();
                RuntimeScalar returned = RuntimeScalar.copyToMortalReturnSlot(source);
                PerlOwnerSlot returnOwner = returned.untrackedReferenceOwnerSlot();
                assertNotNull(returnOwner);
                assertEquals(2,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.popAndFlush();
                    assertFalse(returnOwner.isActive());
                    assertEquals(1,
                            target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                    assertTrue(WeakRefRegistry.isweak(weakObserver),
                            "the source lexical still owns the referent");

                    RuntimeScalar.scopeExitCleanup(source);
                    MyVarCleanupStack.unregister(source);
                    assertEquals(0,
                            target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                    assertFalse(WeakRefRegistry.isweak(weakObserver));
                    assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                    assertEquals(0, measurement.stats().scalarReachabilityQueries);
                }
            } finally {
                RuntimeScalar.scopeExitCleanup(source);
                MyVarCleanupStack.unregister(source);
                MyVarCleanupStack.popMark(lexicalMark);
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
            }
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase value) {
        RuntimeScalar reference = new RuntimeScalar();
        reference.type = RuntimeScalarType.REFERENCE;
        reference.value = value;
        return reference;
    }
}
