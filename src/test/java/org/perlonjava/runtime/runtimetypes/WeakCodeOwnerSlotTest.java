package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class WeakCodeOwnerSlotTest {
    @Test
    void packageArrayCodeOwnerSlotSkipsReachabilityFallbacks() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeScalar strongCodeRef = new RuntimeScalar(code);
            RuntimeArray callbacks = new RuntimeArray();
            callbacks.addClonedElement(strongCodeRef);
            String globalName = "WeakCodeOwnerSlotTest::callbacks";
            GlobalVariable.globalArrays.put(globalName, callbacks);

            RuntimeScalar weakCodeRef = new RuntimeScalar(code);
            WeakRefRegistry.weaken(weakCodeRef);
            try {
                assertTrue(code.hasOwnerSlots());
                assertTrue(code.ownerSlotSnapshot().stream().anyMatch(
                                slot -> slot.isActive()
                                        && slot.kind() != PerlOwnerSlot.Kind.CODE_SLOT),
                        "package arrays create a direct owner identity for their CODE values");
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    WeakRefRegistry.clearWeakRefsTo(code);

                    assertTrue(WeakRefRegistry.isweak(weakCodeRef));
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                    assertEquals(0, measurement.stats().scalarReachabilityQueries);
                }
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
                callbacks.elements.remove(strongCodeRef);
                MortalList.deferDestroyForContainerClear(java.util.List.of(strongCodeRef));
                if (WeakRefRegistry.isweak(weakCodeRef)) {
                    WeakRefRegistry.unweaken(weakCodeRef);
                }
            }
        }
    }

    @Test
    void liveLexicalOwnerSlotSkipsCodeScalarGraphScan() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            code.refCount = 0;
            RuntimeScalar owner = new RuntimeScalar();
            int mark = MyVarCleanupStack.pushMark();
            MyVarCleanupStack.register(owner);
            owner.set(new RuntimeScalar(code));
            RuntimeScalar observer = new RuntimeScalar(code);
            WeakRefRegistry.weaken(observer);

            try {
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    WeakRefRegistry.clearWeakRefsTo(code);

                    assertTrue(WeakRefRegistry.isweak(observer));
                    assertEquals(0, measurement.stats().scalarReachabilityQueries,
                            "the live lexical owner identity avoids the scalar-root scan");
                    assertEquals(0, measurement.stats().rootQueries);
                }
            } finally {
                owner.set(new RuntimeScalar());
                MyVarCleanupStack.unregister(owner);
                MyVarCleanupStack.popMark(mark);
            }
            assertEquals(RuntimeScalarType.UNDEF, observer.type,
                    "the final lexical owner release clears the weak CODE observer");
        }
    }

}
