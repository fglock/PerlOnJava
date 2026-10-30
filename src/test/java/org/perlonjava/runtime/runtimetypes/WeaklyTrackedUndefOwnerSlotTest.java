package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class WeaklyTrackedUndefOwnerSlotTest {
    @Test
    void undefiningOneUntrackedCellPreservesTheOtherOwnerUntilItsRelease() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "the owner-slot transition must work without corrective sweeps");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray target = new RuntimeArray();
            RuntimeScalar firstOwner = referenceTo(target);
            RuntimeScalar secondOwner = referenceTo(target);
            RuntimeScalar weakObserver = referenceTo(target);
            int mark = MyVarCleanupStack.pushMark();
            try {
                MyVarCleanupStack.register(firstOwner);
                MyVarCleanupStack.register(secondOwner);

                PerlOwnerSlot firstSlot = firstOwner.untrackedReferenceOwnerSlot();
                PerlOwnerSlot secondSlot = secondOwner.untrackedReferenceOwnerSlot();
                assertEquals(-1, target.refCount);
                assertTrue(firstSlot.isActive());
                assertTrue(secondSlot.isActive());

                WeakRefRegistry.weaken(weakObserver);
                assertEquals(WeakRefRegistry.WEAKLY_TRACKED, target.refCount);

                firstOwner.undefine();

                assertFalse(firstSlot.isActive(), "undef releases this cell's owner exactly once");
                assertTrue(secondSlot.isActive(), "the other scalar cell still owns the referent");
                assertTrue(WeakRefRegistry.isweak(weakObserver));
                assertNotEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        target.perlLifecycleState());

                secondOwner.undefine();

                assertFalse(secondSlot.isActive());
                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "the final owner release clears the weak observer");
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        target.perlLifecycleState());
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                RuntimeScalar.scopeExitCleanup(firstOwner);
                RuntimeScalar.scopeExitCleanup(secondOwner);
                MyVarCleanupStack.unregister(firstOwner);
                MyVarCleanupStack.unregister(secondOwner);
                MyVarCleanupStack.popMark(mark);
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
