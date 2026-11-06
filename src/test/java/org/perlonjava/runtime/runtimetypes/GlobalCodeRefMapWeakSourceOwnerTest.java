package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class GlobalCodeRefMapWeakSourceOwnerTest {
    @Test
    void stashSlotCopiesWeakCodeValueAndOwnsItsCountedEdge() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            GlobalVariable.resetAllGlobals();
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            code.refCount = 0;

            RuntimeScalar otherStrongOwner = new RuntimeScalar(code);
            RuntimeScalar.incrementRefCountForContainerStore(
                    otherStrongOwner, PerlOwnerSlot.Kind.SCALAR_CELL);
            RuntimeScalar weakSource = new RuntimeScalar(code);
            WeakRefRegistry.weaken(weakSource);
            String name = "GlobalCodeRefMapWeakSourceOwnerTest::target";

            try {
                int countBeforeStashStore = code.refCount;
                GlobalVariable.globalCodeRefs.put(name, weakSource);
                RuntimeScalar stashSlot = GlobalVariable.globalCodeRefs.get(name);

                assertNotSame(weakSource, stashSlot,
                        "the stash stores its own scalar slot, not the weak source cell");
                assertTrue(WeakRefRegistry.isweak(weakSource),
                        "storing into a glob does not strengthen the source scalar");
                assertFalse(WeakRefRegistry.isweak(stashSlot),
                        "the stash CODE slot is a strong Perl owner");
                assertTrue(stashSlot.refCountOwned);
                assertSame(code, stashSlot.countedOwnerSlot().referent());
                assertEquals(countBeforeStashStore + 1, code.refCount,
                        "the stash slot acquires its own counted owner");

                GlobalVariable.globalCodeRefs.remove(name);
                assertEquals(countBeforeStashStore, code.refCount,
                        "removing the stash slot releases only its own count");
                assertTrue(WeakRefRegistry.isweak(weakSource));
            } finally {
                GlobalVariable.globalCodeRefs.remove(name);
                if (WeakRefRegistry.isweak(weakSource)) {
                    WeakRefRegistry.unweaken(weakSource);
                }
                if (weakSource.refCountOwned) {
                    MortalList.releaseCountedOwnerImmediately(
                            weakSource, "GlobalCodeRefMapWeakSourceOwnerTest weak cleanup");
                }
                if (otherStrongOwner.refCountOwned) {
                    MortalList.releaseCountedOwnerImmediately(
                            otherStrongOwner, "GlobalCodeRefMapWeakSourceOwnerTest cleanup");
                }
                GlobalVariable.resetAllGlobals();
            }
        }
    }
}
