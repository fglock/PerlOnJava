package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class LiveAggregateValueOwnerSlotTest {
    @Test
    void borrowedCellInLiveAggregateRetainsTrackedReferentUntilSlotLeaves() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            int lexicalMark = MyVarCleanupStack.pushMark();
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar countedOwner = new RuntimeScalar();
            countedOwner.set(referent.createAnonymousReference());

            RuntimeArray ownerContainer = new RuntimeArray();
            MyVarCleanupStack.register(ownerContainer);
            RuntimeScalar uncountedOwner = referent.createAnonymousReference();
            ownerContainer.elements.add(uncountedOwner);
            PerlOwnerSlot valueOwner = uncountedOwner.untrackedReferenceOwnerSlot();

            RuntimeScalar weakObserver = referent.createAnonymousReference();
            WeakRefRegistry.weaken(weakObserver);
            try {
                assertNotNull(valueOwner,
                        "the live aggregate cell owns its uncounted value edge");
                assertEquals(1,
                        referent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                countedOwner.set(new RuntimeScalar());

                assertEquals(WeakRefRegistry.WEAKLY_TRACKED, referent.refCount);
                assertTrue(WeakRefRegistry.isweak(weakObserver),
                        "the aggregate cell keeps the weakly observed referent alive");

                uncountedOwner.set(new RuntimeScalar());

                assertFalse(valueOwner.isActive(), "the value owner leaves exactly once");
                assertEquals(0,
                        referent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "the final owner release clears weak observers");
                assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
                assertEquals(Integer.MIN_VALUE, referent.refCount);
            } finally {
                if (valueOwner != null && valueOwner.isActive()) {
                    uncountedOwner.set(new RuntimeScalar());
                }
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                countedOwner.set(new RuntimeScalar());
                MyVarCleanupStack.popMark(lexicalMark);
            }
        }
    }
}
