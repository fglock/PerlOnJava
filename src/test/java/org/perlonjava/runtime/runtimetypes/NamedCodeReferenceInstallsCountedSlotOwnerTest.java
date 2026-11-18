package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class NamedCodeReferenceInstallsCountedSlotOwnerTest {
    @Test
    void undefinedBoundStashSlotAcquiresOwnerWhenNamedReferenceVivifiesCode() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            GlobalVariable.resetAllGlobals();
            String name = "NamedCodeReferenceInstallsCountedSlotOwnerTest::target";
            RuntimeCode original = new RuntimeCode((args, context) -> new RuntimeList(), null);
            GlobalVariable.globalCodeRefs.put(name, new RuntimeScalar(original));
            RuntimeGlob glob = new RuntimeGlob(name);
            boolean localized = false;

            try {
                glob.dynamicSaveState();
                localized = true;
                RuntimeScalar slot = GlobalVariable.globalCodeRefs.get(name);
                assertTrue(slot.hasCodeSlotBinding(), "the stash owns the empty CODE slot");
                RuntimeScalar resolved = GlobalVariable.getGlobalCodeRefForNamedReference(name);
                RuntimeCode code = (RuntimeCode) resolved.value;
                PerlOwnerSlot ownerSlot = resolved.countedOwnerSlot();

                assertSame(slot, resolved);
                assertEquals(name, resolved.globalCodeRefFqn);
                assertTrue(resolved.refCountOwned,
                        "vivifying the value must acquire through the bound scalar slot");
                assertNotNull(ownerSlot);
                assertEquals(PerlOwnerSlot.Kind.CODE_SLOT,
                        ownerSlot.kind());
                assertSame(resolved, ownerSlot.countedScalarOwner());
                assertSame(code, ownerSlot.referent());
                assertEquals(1, code.refCount);
                assertTrue(code.hasCountedCodeSlots);

                localized = false;
                glob.dynamicRestoreState();

                assertSame(original, GlobalVariable.globalCodeRefs.get(name).value);
                assertFalse(resolved.refCountOwned,
                        "restore retires the exact localized CODE-slot token");
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        code.perlLifecycleState(),
                        "the now ownerless forward CV is destroyed on local restore");
            } finally {
                if (localized) {
                    glob.dynamicRestoreState();
                }
                GlobalVariable.globalCodeRefs.remove(name);
                GlobalVariable.resetAllGlobals();
            }
        }
    }
}
