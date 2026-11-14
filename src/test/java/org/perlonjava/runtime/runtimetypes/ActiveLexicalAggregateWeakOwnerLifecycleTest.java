package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ActiveLexicalAggregateWeakOwnerLifecycleTest {
    @Test
    void weakeningLastCountedReferenceDefersToTheActiveLexicalOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeCode.pushActiveCode(code);
            int mark = MyVarCleanupStack.pushMark();
            RuntimeArray array = new RuntimeArray();
            RuntimeScalar weakOwner = new RuntimeScalar();
            try {
                MyVarCleanupStack.register(array);
                RuntimeCode.registerCurrentActiveLexical("@pad", array);
                weakOwner.set(array.createReference());

                assertEquals(1, array.refCount);
                assertEquals(1, array.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL));

                {
                    WeakRefRegistry.weaken(weakOwner);

                    assertTrue(WeakRefRegistry.isweak(weakOwner));
                    assertEquals(RuntimeBase.RefCountTrackingState.WEAKLY_TRACKED, array.refCountTrackingState(),
                            "the active lexical edge must not be fabricated as a count");
                    assertTrue(array.deferredFinalReleaseForUncountedOwners,
                            "the zero-count lifecycle must wait on the exact pad owner");

                    MortalList.scopeExitCleanupArray(array);
                    MyVarCleanupStack.unregister(array);

                    assertEquals(0, array.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL));
                    assertEquals(RuntimeScalarType.UNDEF, weakOwner.type);
                    assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, array.perlLifecycleState());
                    assertEquals(0, array.refCount);
                    assertFalse(array.deferredFinalReleaseForUncountedOwners);
                }
            } finally {
                MyVarCleanupStack.unregister(array);
                MyVarCleanupStack.popMark(mark);
                RuntimeCode.popActiveCode(code);
                if (WeakRefRegistry.isweak(weakOwner)) WeakRefRegistry.unweaken(weakOwner);
            }
        }
    }
}
