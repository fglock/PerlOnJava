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

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    WeakRefRegistry.weaken(weakOwner);

                    assertTrue(WeakRefRegistry.isweak(weakOwner));
                    assertEquals(WeakRefRegistry.WEAKLY_TRACKED, array.refCount,
                            "the active lexical edge must not be fabricated as a count");
                    assertTrue(array.deferredFinalReleaseForUncountedOwners,
                            "the zero-count lifecycle must wait on the exact pad owner");
                    assertEquals(0, measurement.stats().rootQueries,
                            "weakening must consult owner identities without walking roots");

                    MortalList.scopeExitCleanupArray(array);
                    MyVarCleanupStack.unregister(array);

                    assertEquals(0, array.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL));
                    assertEquals(RuntimeScalarType.UNDEF, weakOwner.type);
                    assertEquals(Integer.MIN_VALUE, array.refCount);
                    assertFalse(array.deferredFinalReleaseForUncountedOwners);
                    assertEquals(0, measurement.stats().rootQueries);
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
