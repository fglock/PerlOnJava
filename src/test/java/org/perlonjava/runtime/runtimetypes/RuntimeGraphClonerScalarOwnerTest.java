package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeGraphClonerScalarOwnerTest {
    @Test
    void clonedCountedScalarAcquiresAndReleasesItsChildOwnerSlot() {
        PerlRuntime sourceRuntime = new PerlRuntime();
        PerlRuntime targetRuntime = new PerlRuntime();
        RuntimeHash sourceReferent = new RuntimeHash();
        RuntimeScalar sourceOwner = new RuntimeScalar();
        try (PerlRuntime.Binding ignored = sourceRuntime.bind()) {
            sourceOwner.set(sourceReferent.createAnonymousReference());
            assertEquals(1, sourceReferent.refCount);
            assertEquals(1, sourceReferent.countedOwnerSlotCount());
        }

        RuntimeScalar clonedOwner = (RuntimeScalar) new RuntimeGraphCloner(
                sourceRuntime, targetRuntime).cloneGraph(sourceOwner);
        RuntimeHash clonedReferent = (RuntimeHash) clonedOwner.value;
        RuntimeScalar weakObserver = new RuntimeScalar();
        try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
            assertEquals(1, clonedReferent.refCount);
            assertEquals(1, clonedReferent.countedOwnerSlotCount());
            assertEquals(1, clonedReferent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
            assertTrue(clonedOwner.refCountOwned);

            weakObserver.set(clonedOwner);
            WeakRefRegistry.weaken(weakObserver);
            assertEquals(1, clonedReferent.refCount);

            clonedOwner.undefine();

            assertFalse(WeakRefRegistry.isweak(weakObserver));
            assertEquals(RuntimeScalarType.UNDEF, weakObserver.type,
                    "releasing the cloned owner clears its final weak observer");
            assertEquals(Integer.MIN_VALUE, clonedReferent.refCount);
        } finally {
            try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                if (clonedOwner.refCountOwned) clonedOwner.undefine();
            }
            try (PerlRuntime.Binding ignored = sourceRuntime.bind()) {
                if (sourceOwner.refCountOwned) sourceOwner.undefine();
            }
        }
    }
}
