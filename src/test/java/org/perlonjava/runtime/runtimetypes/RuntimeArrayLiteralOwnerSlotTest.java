package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeArrayLiteralOwnerSlotTest {
    @Test
    void copiedLiteralElementHasCurrentArrayElementOwnerIdentity() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar value = referent.createAnonymousReference();
            RuntimeArray literal = new RuntimeArray();

            literal.add(value);
            literal.createReferenceWithTrackedElements();

            RuntimeScalar stored = literal.elements.getFirst();
            PerlOwnerSlot owner = stored.countedOwnerSlot();
            assertSame(literal, stored.getContainerOwner());
            assertSame(stored, owner.countedScalarOwner());
            assertEquals(PerlOwnerSlot.Kind.ARRAY_ELEMENT, owner.kind());
            assertTrue(stored.isCurrentAggregateElementOwner(referent, owner));
            assertEquals(1, referent.refCount);
            assertTrue(referent.restoreVerifiedCurrentCountedOwnerSlotRefCount(),
                    "the live array slot proves its count without a root walk");
        }
    }
}
