package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class ActiveLexicalAggregateOwnerSlotTest {
    @Test
    void activeArrayAndHashPadsOwnAndReleaseAggregateSlotsWithoutSweeps() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "this lifecycle regression requires corrective sweeps to be disabled");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeCode.pushActiveCode(code);
            int mark = MyVarCleanupStack.pushMark();
            RuntimeArray array = new RuntimeArray();
            RuntimeHash hash = new RuntimeHash();
            RuntimeScalar weakArray = weakReferenceTo(array);
            RuntimeScalar weakHash = weakReferenceTo(hash);
            try {
                MyVarCleanupStack.register(array);
                MyVarCleanupStack.register(hash);
                RuntimeCode.registerCurrentActiveLexical("@pad", array);
                RuntimeCode.registerCurrentActiveLexical("%pad", hash);
                WeakRefRegistry.weaken(weakArray);
                WeakRefRegistry.weaken(weakHash);

                assertEquals(1, array.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL));
                assertEquals(1, hash.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL));
                assertTrue(WeakRefRegistry.isweak(weakArray));
                assertTrue(WeakRefRegistry.isweak(weakHash));

                MortalList.scopeExitCleanupArray(array);
                MyVarCleanupStack.unregister(array);
                assertEquals(0, array.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL));
                assertFalse(WeakRefRegistry.isweak(weakArray),
                        "the final array-pad release clears its weak observer immediately");

                MortalList.scopeExitCleanupHash(hash);
                MyVarCleanupStack.unregister(hash);
                assertEquals(0, hash.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL));
                assertFalse(WeakRefRegistry.isweak(weakHash),
                        "the final hash-pad release clears its weak observer immediately");
            } finally {
                MyVarCleanupStack.unregister(array);
                MyVarCleanupStack.unregister(hash);
                MyVarCleanupStack.popMark(mark);
                RuntimeCode.popActiveCode(code);
                if (WeakRefRegistry.isweak(weakArray)) WeakRefRegistry.unweaken(weakArray);
                if (WeakRefRegistry.isweak(weakHash)) WeakRefRegistry.unweaken(weakHash);
            }
        }
    }

    private static RuntimeScalar weakReferenceTo(RuntimeBase target) {
        return target.createReference();
    }
}
