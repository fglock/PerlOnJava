package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class UntrackedAggregateStorageReleaseTest {
    @Test
    void retiringTheLastStorageOwnerReleasesDirectNestedOwnerSlots() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeArray outer = new RuntimeArray();
            RuntimeHash inner = new RuntimeHash();
            RuntimeScalar innerOwner = new RuntimeScalar();
            innerOwner.type = RuntimeScalarType.HASHREFERENCE;
            innerOwner.value = inner;
            outer.elements.add(innerOwner);
            innerOwner.markContainerOwner(outer);
            innerOwner.refreshUntrackedReferenceOwner();

            RuntimeHash storage = new RuntimeHash();
            RuntimeScalar outerOwner = new RuntimeScalar();
            outerOwner.type = RuntimeScalarType.ARRAYREFERENCE;
            outerOwner.value = outer;
            storage.elements.put("outer", outerOwner);
            outerOwner.markContainerOwner(storage);
            outerOwner.refreshUntrackedReferenceOwner();

            assertEquals(1,
                    outer.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
            storage.elements.clear();
            outerOwner.releaseContainerOwner(storage);

            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                    outer.perlLifecycleState());
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                    inner.perlLifecycleState(),
                    "the final parent storage edge releases its direct nested slot");
        }
    }
}
