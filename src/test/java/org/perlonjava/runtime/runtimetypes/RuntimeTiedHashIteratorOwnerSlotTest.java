package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeTiedHashIteratorOwnerSlotTest {
    @Test
    void currentKeyUsesOneCountedOwnerSlotAndResetBalancesItsCount() throws Exception {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash hash = new RuntimeHash();
            hash.type = RuntimeHash.TIED_HASH;
            Iterator<RuntimeScalar> iterator = hash.iterator();
            hash.hashIterator = iterator;

            RuntimeArray currentReferent = new RuntimeArray();
            currentReferent.refCount = 0;
            RuntimeScalar currentKey = referenceTo(currentReferent);
            PerlOwnerSlot currentSlot = retainIteratorKey(iterator, currentKey);
            setField(iterator, "currentKey", currentKey);
            setField(iterator, "currentKeyOwnerSlot", currentSlot);

            assertTrue(currentSlot.isCountedNativeOwner());
            assertEquals(1, currentReferent.refCount);

            currentReferent.refCount = 0;
            assertTrue(currentReferent.restoreVerifiedCurrentCountedOwnerSlotRefCount());
            assertEquals(1, currentReferent.refCount,
                    "the live iterator slot restores its paired count");

            currentReferent.refCount = 2;
            hash.resetIterator();

            assertFalse(currentSlot.isActive());
            assertEquals(0, currentReferent.ownerSlotCount(
                    PerlOwnerSlot.Kind.TIED_HASH_ITERATOR_KEY));
            assertEquals(1, currentReferent.refCount);
            assertNull(hash.hashIterator);
        }
    }

    @Test
    void uncountedIteratorKeyPreservesAndThenClearsWeakObservers() throws Exception {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash hash = new RuntimeHash();
            hash.type = RuntimeHash.TIED_HASH;
            Iterator<RuntimeScalar> iterator = hash.iterator();
            hash.hashIterator = iterator;

            RuntimeArray referent = new RuntimeArray();
            referent.refCount = -1;
            RuntimeScalar weak = referenceTo(referent);
            WeakRefRegistry.weaken(weak);
            assertEquals(WeakRefRegistry.WEAKLY_TRACKED, referent.refCount);
            assertTrue(WeakRefRegistry.isweak(weak));

            RuntimeScalar currentKey = referenceTo(referent);
            PerlOwnerSlot slot = retainIteratorKey(iterator, currentKey);
            setField(iterator, "currentKey", currentKey);
            setField(iterator, "currentKeyOwnerSlot", slot);

            assertFalse(slot.isCountedNativeOwner());
            assertTrue(WeakRefRegistry.isweak(weak),
                    "the uncounted iterator edge keeps the weak observer alive");

            hash.resetIterator();

            assertFalse(slot.isActive());
            assertEquals(RuntimeScalarType.UNDEF, weak.type,
                    "releasing the final iterator owner clears the weak observer");
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.HASHREFERENCE;
        scalar.value = referent;
        return scalar;
    }

    private static PerlOwnerSlot retainIteratorKey(
            Iterator<RuntimeScalar> iterator, RuntimeScalar key) throws Exception {
        Method retain = iterator.getClass().getDeclaredMethod(
                "retainIteratorKey", RuntimeScalar.class);
        retain.setAccessible(true);
        return (PerlOwnerSlot) retain.invoke(iterator, key);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
