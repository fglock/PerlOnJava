package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
class TailCallCodeRefDoesNotPinFromLocalBindingFlagTest {
    @Test
    void staleLocalBindingMarkerCannotReplaceTheReleasedCodeSlot() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            code.refCount = 1;
            // RuntimeCode has no producer for this aggregate/scalar binding
            // marker. It must not fabricate a counted owner at tail-call exit.
            code.localBindingExists = true;

            RuntimeScalar codeRef = new RuntimeScalar();
            codeRef.type = RuntimeScalarType.CODE;
            codeRef.value = code;
            codeRef.refCountOwned = true;
            code.recordActiveOwner(codeRef);

            MortalList.releaseTailCallCodeRef(codeRef);

            assertFalse(codeRef.refCountOwned);
            assertEquals(0, code.countedOwnerSlotCount());
            assertEquals(Integer.MIN_VALUE, code.refCount,
                    "the final real CODE slot release must complete lifecycle cleanup");
        }
    }
}
