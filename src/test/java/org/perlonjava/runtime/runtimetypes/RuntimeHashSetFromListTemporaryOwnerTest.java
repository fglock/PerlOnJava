package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class RuntimeHashSetFromListTemporaryOwnerTest {
    @Test
    void releasingHashAssignmentTemporariesRetiresTheirUntrackedValueOwners() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.refCount = WeakRefRegistry.WEAKLY_TRACKED;

            RuntimeScalar sourceValue = new RuntimeScalar();
            sourceValue.type = RuntimeScalarType.HASHREFERENCE;
            sourceValue.value = referent;

            RuntimeHash target = new RuntimeHash();
            RuntimeArray assignmentResult = target.setFromList(new RuntimeList(
                    new RuntimeScalar("item"), sourceValue));

            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE),
                    "the private RHS materialization releases its value owner before returning");

            assignmentResult.elements.clear();
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE),
                    "the returned assignment value is the hash, not another value edge");

            target.elements.clear();
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE),
                    "hash assignment releases its private RHS materialization when it returns");
        }
    }
}
