package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class UnownedTemporaryArrayAggregateOwnerTest {
    @Test
    void countedElementInUnownedTemporaryArrayIsNotACurrentPerlOwner() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeArray temporary = new RuntimeArray();
            temporary.localBindingExists = true;

            RuntimeScalar owner = referent.createAnonymousReference();
            RuntimeScalar.incrementRefCountForContainerStore(
                    owner, PerlOwnerSlot.Kind.ARRAY_ELEMENT);
            temporary.elements.add(owner);
            assertTrue(referent.hasCurrentPerlOwnerSlotIdentity(),
                    "a stored element in a live array is a current Perl owner");

            owner.localBindingExists = false;
            temporary.localBindingExists = false;
            assertFalse(temporary.hasPerlStrongOwner());
            assertFalse(referent.hasCurrentPerlOwnerSlotIdentity(),
                    "an element in an unowned temporary array is not a Perl owner");
        }
    }
}
