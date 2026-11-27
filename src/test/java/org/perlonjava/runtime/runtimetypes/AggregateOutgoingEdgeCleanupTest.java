package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class AggregateOutgoingEdgeCleanupTest {
    @Test
    void hashCleanupReleasesNestedArrayOutgoingEdge() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash child = blessedHash("HashChild");
            RuntimeScalar childReference = child.createAnonymousReference();
            RuntimeArray nested = new RuntimeArray();
            nested.push(childReference);
            RuntimeHash parent = new RuntimeHash();
            parent.put("nested", nested.createAnonymousReference());
            RuntimeScalar childWeakReference = child.createAnonymousReference();

            WeakRefRegistry.weaken(childWeakReference);
            assertEquals(1,
                    child.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));

            MortalList.scopeExitCleanupHash(parent);
            MortalList.flush();

            assertEquals(0,
                    child.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
            assertFalse(WeakRefRegistry.isweak(childWeakReference));
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                    child.perlLifecycleState());
        }
    }

    @Test
    void arrayCleanupReleasesNestedHashOutgoingEdge() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash child = blessedHash("ArrayChild");
            RuntimeScalar childReference = child.createAnonymousReference();
            RuntimeHash nested = new RuntimeHash();
            nested.put("child", childReference);
            RuntimeArray parent = new RuntimeArray();
            parent.push(nested.createAnonymousReference());
            RuntimeScalar childWeakReference = child.createAnonymousReference();

            WeakRefRegistry.weaken(childWeakReference);
            assertEquals(1,
                    child.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));

            MortalList.scopeExitCleanupArray(parent);
            MortalList.flush();

            assertEquals(0,
                    child.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));
            assertFalse(WeakRefRegistry.isweak(childWeakReference));
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                    child.perlLifecycleState());
        }
    }

    @Test
    void liveNestedAggregateOwnerKeepsItsOutgoingEdges() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash child = blessedHash("SharedChild");
            RuntimeScalar childReference = child.createAnonymousReference();
            RuntimeArray nested = new RuntimeArray();
            nested.push(childReference);
            RuntimeArray parent = new RuntimeArray();
            parent.push(nested.createAnonymousReference());
            RuntimeScalar nestedKeeper = new RuntimeScalar();
            nestedKeeper.localBindingExists = true;
            nestedKeeper.set(nested.createAnonymousReference());
            RuntimeScalar childWeakReference = child.createAnonymousReference();

            WeakRefRegistry.weaken(childWeakReference);
            assertEquals(1,
                    child.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));

            MortalList.scopeExitCleanupArray(parent);
            MortalList.flush();

            assertTrue(WeakRefRegistry.isweak(childWeakReference));
            assertEquals(1,
                    child.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
            assertTrue(nested.hasCurrentPerlStorageOwner());

            RuntimeScalar.scopeExitCleanup(nestedKeeper);
            MortalList.flush();
            assertFalse(WeakRefRegistry.isweak(childWeakReference));
            assertEquals(0,
                    child.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
            WeakRefRegistry.unweaken(childWeakReference);
        }
    }

    private static RuntimeHash blessedHash(String suffix) {
        RuntimeHash hash = new RuntimeHash();
        hash.setBlessId(NameNormalizer.getBlessId(
                "AggregateOutgoingEdgeCleanupTest::" + suffix));
        return hash;
    }
}
