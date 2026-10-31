package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CodePadConstantOwnerSlotTest {
    @Test
    void eachOptreeConstantEdgeLivesUntilItsCvOwnerIsDestroyed() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeScalarReadOnly constant = new RuntimeScalarReadOnly("owned");
        RuntimeCode firstCode = new RuntimeCode((args, context) -> new RuntimeList(), null);
        RuntimeCode secondCode = new RuntimeCode((args, context) -> new RuntimeList(), null);
        RuntimeScalar weakObserver = referenceTo(constant);

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            MortalList.setActive(true);
            try {
                firstCode.installPadConstants(new RuntimeBase[]{constant, constant});
                secondCode.installPadConstants(new RuntimeBase[]{constant});

                PerlOwnerSlot[] owners = constant.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.CODE_PAD_CONSTANT)
                        .toArray(PerlOwnerSlot[]::new);
                assertEquals(3, owners.length,
                        "each constant occurrence in each CV has a distinct strong slot");
                assertEquals(3, constant.ownerSlotCount(PerlOwnerSlot.Kind.CODE_PAD_CONSTANT));

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    WeakRefRegistry.weaken(weakObserver);

                    assertTrue(WeakRefRegistry.isweak(weakObserver));
                    assertTrue(RuntimeCode.isInstalledPadConstant(constant));
                    assertEquals(0, measurement.stats().rootQueries,
                            "optree owner identities replace the global CODE scan");
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                // A returned reference contributes a real temporary count,
                // but once it leaves the mortal boundary only the CODE pad
                // slots remain. Preserve those slots without inventing a
                // replacement refCount owner.
                constant.refCount = 1;
                MortalList.deferDecrement(constant);
                MortalList.flush();
                assertEquals(WeakRefRegistry.WEAKLY_TRACKED, constant.refCount,
                        "pad ownership defers cleanup without fabricating a counted owner");
                assertTrue(WeakRefRegistry.isweak(weakObserver));

                firstCode.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(firstCode);
                assertEquals(1, constant.ownerSlotCount(PerlOwnerSlot.Kind.CODE_PAD_CONSTANT));
                assertTrue(WeakRefRegistry.isweak(weakObserver),
                        "one surviving CV still owns the shared optree constant");

                secondCode.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(secondCode);
                assertEquals(0, constant.ownerSlotCount(PerlOwnerSlot.Kind.CODE_PAD_CONSTANT));
                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "the final CV release clears the constant's weak observers");
                assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
                for (PerlOwnerSlot owner : owners) assertFalse(owner.isActive());

                DestroyDispatch.callDestroy(secondCode);
                assertEquals(0, constant.ownerSlotCount(PerlOwnerSlot.Kind.CODE_PAD_CONSTANT),
                        "repeated destruction cannot release an owner twice");
            } finally {
                if (firstCode.perlLifecycleState() != RuntimeBase.PerlLifecycleState.DESTROYED) {
                    firstCode.refCount = Integer.MIN_VALUE;
                    DestroyDispatch.callDestroy(firstCode);
                }
                if (secondCode.perlLifecycleState() != RuntimeBase.PerlLifecycleState.DESTROYED) {
                    secondCode.refCount = Integer.MIN_VALUE;
                    DestroyDispatch.callDestroy(secondCode);
                }
                WeakRefRegistry.clearWeakRefsTo(constant);
                MortalList.invalidateAllRootSnapshots();
                MortalList.setActive(previousMortalListState);
            }
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.REFERENCE;
        scalar.value = referent;
        return scalar;
    }
}
