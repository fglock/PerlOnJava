package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class StashCodeLifecycleUsesOwnerIdentityTest {
    @Test
    void releasedStashSlotRetiresCodeDespiteStaleCompatibilityCounter() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            String name = "StashCodeLifecycleUsesOwnerIdentityTest::installed";
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeScalar stashSlot = new RuntimeScalar();
            stashSlot.type = RuntimeScalarType.CODE;
            stashSlot.value = code;
            GlobalVariable.globalCodeRefs.put(name, stashSlot);

            assertTrue(code.hasStashCodeSlotOwner(),
                    "the visible stash cell owns the CODE through its slot identity");
            code.stashRefCount = 7; // Deliberately stale legacy metadata.

            GlobalVariable.removeGlobalCodeRefForStashDelete(name);

            assertFalse(code.hasStashCodeSlotOwner(),
                    "deleting the stash entry retires its exact CODE_SLOT owner");
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                    code.perlLifecycleState(),
                    "a stale compatibility counter cannot preserve a CODE with no Perl owners");
        }
    }
}
