package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class DeferredUnblessedAggregateOwnerSlotRepairTest {
    @Test
    void removingArrayElementReleasesItsExactOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            MortalList.setActive(true);
            RuntimeHash target = new RuntimeHash();
            RuntimeArray ownerArray = new RuntimeArray();
            ownerArray.push(target.createAnonymousReference());
            RuntimeScalar weakObserver = target.createAnonymousReference();
            WeakRefRegistry.weaken(weakObserver);

            try {
                assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
                assertTrue(WeakRefRegistry.isweak(weakObserver),
                        "the current array element owns the target");
                RuntimeArray.pop(ownerArray);
                MortalList.flush();
                assertFalse(WeakRefRegistry.isweak(weakObserver));
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) WeakRefRegistry.unweaken(weakObserver);
                if (!ownerArray.elements.isEmpty()) {
                    ownerArray.elements.getFirst().set(new RuntimeScalar());
                }
                MortalList.setActive(previousMortalListState);
            }
        }
    }
}
