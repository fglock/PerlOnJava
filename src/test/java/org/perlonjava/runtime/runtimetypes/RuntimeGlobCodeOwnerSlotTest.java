package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeGlobCodeOwnerSlotTest {
    @Test
    void restoringLocalizedGlobRetiresTrackedTemporaryCodeOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String name = "LocalizedCodeOwner::callback";
            RuntimeCode localCodeValue = new RuntimeCode(
                    (args, context) -> new RuntimeList(), null);
            localCodeValue.refCount = 0;
            RuntimeScalar localCodeSource = new RuntimeScalar(localCodeValue);
            RuntimeGlob glob = new RuntimeGlob(name);

            glob.dynamicSaveState();
            RuntimeScalar localCode = GlobalVariable.globalCodeRefs.get(name);
            localCode.set(localCodeSource);
            PerlOwnerSlot ownerSlot = localCode.scalarValueOwnerSlot();

            assertNotNull(ownerSlot);
            assertTrue(ownerSlot.isActive());
            assertEquals(1, localCodeValue.refCount);
            assertEquals(1, localCodeValue.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));

            glob.dynamicRestoreState();

            assertFalse(ownerSlot.isActive(),
                    "restoring the glob retires the temporary CODE scalar owner slot");
            assertEquals(0, localCodeValue.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
            assertEquals(Integer.MIN_VALUE, localCodeValue.refCount,
                    "the final temporary CODE owner still dispatches cleanup");
        }
    }
}
