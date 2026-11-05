package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class GlobalCodeSlotUndefOwnerTransitionTest {
    @Test
    void undefGlobalCodeSlotPublishesReplacementBeforeReleasingOldCode() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            GlobalVariable.resetAllGlobals();
            try {
                String name = "GlobalCodeSlotUndefOwnerTransitionTest::target";
                RuntimeCode oldCode = new RuntimeCode((String) null, null);
                RuntimeScalar slot = new RuntimeScalar(oldCode);
                GlobalVariable.globalCodeRefs.put(name, slot);

                assertTrue(slot.refCountOwned);
                assertSame(oldCode, slot.countedOwnerSlot().referent());
                assertEquals(1, oldCode.refCount);

                slot.undefine();

                RuntimeCode undefinedCode = (RuntimeCode) slot.value;
                assertNotSame(oldCode, undefinedCode);
                assertSame(slot, GlobalVariable.globalCodeRefs.get(name));
                assertSame(undefinedCode, slot.countedOwnerSlot().referent());
                assertEquals(1, undefinedCode.refCount);
                assertEquals(1, undefinedCode.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));
                assertEquals(0, oldCode.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));
                assertEquals(Integer.MIN_VALUE, oldCode.refCount);
            } finally {
                GlobalVariable.resetAllGlobals();
            }
        }
    }
}
