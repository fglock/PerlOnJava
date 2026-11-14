package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class CountedArrayOwnerSlotTest {
    @Test
    void countedArraySlotsRetainNestedReferents() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray parent = new RuntimeArray();
            RuntimeHash child = new RuntimeHash();
            child.setTrackedRefCount(0);
            parent.addClonedElement(child.createAnonymousReference());

            assertEquals(1, child.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
            assertEquals(1, child.refCount);
        }
    }
}
