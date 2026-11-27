package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class StrongCycleOwnerSlotTest {
    @Test
    void hashCycleLivesThroughItsHashElementOwnerUntilTheEdgeIsRemoved() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash hash = new RuntimeHash();
            hash.put("self", hash.createAnonymousReference());
            RuntimeScalar weakObserver = hash.createAnonymousReference();
            WeakRefRegistry.weaken(weakObserver);

            try {
                assertEquals(1, hash.refCount);
                assertEquals(1, hash.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));
                {
                    MortalList.scopeExitCleanupHash(hash);

                    assertEquals(1, hash.refCount);
                    assertTrue(WeakRefRegistry.isweak(weakObserver));
                }

                hash.elements.get("self").set(new RuntimeScalar());
                assertFalse(WeakRefRegistry.isweak(weakObserver));
                assertEquals(0, hash.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        hash.perlLifecycleState());
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) WeakRefRegistry.unweaken(weakObserver);
                hash.elements.clear();
            }
        }
    }

    @Test
    void arrayCycleLivesThroughItsArrayElementOwnerUntilTheEdgeIsRemoved() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeArray array = new RuntimeArray();
            array.push(array.createAnonymousReference());
            RuntimeScalar weakObserver = array.createAnonymousReference();
            WeakRefRegistry.weaken(weakObserver);

            try {
                assertEquals(1, array.refCount);
                assertEquals(1, array.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
                {
                    MortalList.scopeExitCleanupArray(array);

                    assertEquals(1, array.refCount);
                    assertTrue(WeakRefRegistry.isweak(weakObserver));
                }

                array.elements.getFirst().set(new RuntimeScalar());
                assertFalse(WeakRefRegistry.isweak(weakObserver));
                assertEquals(0, array.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        array.perlLifecycleState());
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) WeakRefRegistry.unweaken(weakObserver);
                array.elements.clear();
            }
        }
    }
}
