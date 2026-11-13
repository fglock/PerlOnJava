package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class WeakUncountedOwnerSlotFastPathTest {
    @Test
    void finalUncountedOwnerUsesSlotIdentityWithoutRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            RuntimeArray target = new RuntimeArray();
            target.refCount = -1;

            RuntimeArray lexicalOwner = new RuntimeArray();
            int mark = MyVarCleanupStack.pushMark();
            MyVarCleanupStack.register(lexicalOwner);
            RuntimeScalar strong = referenceTo(target);
            lexicalOwner.elements.add(strong);
            PerlOwnerSlot strongSlot = strong.untrackedReferenceOwnerSlot();
            assertNotNull(strongSlot);

            RuntimeScalar weakObserver = referenceTo(target);
            // Model the counted slot being weakened; the array element remains
            // the only uncounted strong Perl owner of the target.
            target.refCount = 1;
            weakObserver.refCountOwned = true;
            try {
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    WeakRefRegistry.weaken(weakObserver);

                    assertTrue(WeakRefRegistry.isweak(weakObserver));
                    assertEquals(WeakRefRegistry.WEAKLY_TRACKED, target.refCount,
                            "an uncounted slot retains the target without a compatibility pin");
                    assertEquals(0, measurement.stats().rootQueries,
                            "the direct owner identity avoids a graph query");
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                strong.set(new RuntimeScalar());

                assertFalse(strongSlot.isActive());
                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "the final uncounted owner release clears the weak observer");
                assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
                assertEquals(Integer.MIN_VALUE, target.refCount);
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                if (strongSlot.isActive()) strong.set(new RuntimeScalar());
                MortalList.scopeExitCleanupArray(lexicalOwner);
                MyVarCleanupStack.unregister(lexicalOwner);
                MyVarCleanupStack.popMark(mark);
            }
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.REFERENCE;
        scalar.value = referent;
        return scalar;
    }
}
