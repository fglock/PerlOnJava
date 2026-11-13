package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class FirstWeakenUsesCurrentPackageOwnerTest {
    @Test
    void currentPackageElementOwnerRepairsTransientZeroWithoutRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String globalName = "FirstWeakenUsesCurrentPackageOwnerTest::root";
            RuntimeArray packageRoot = new RuntimeArray();
            RuntimeHash target = new RuntimeHash();
            GlobalVariable.globalArrays.put(globalName, packageRoot);

            RuntimeScalar packageElement = target.createAnonymousReference();
            packageRoot.addClonedElement(packageElement);
            RuntimeScalar weakOwner = new RuntimeScalar();
            MyVarCleanupStack.register(weakOwner);
            weakOwner.set(target.createAnonymousReference());

            try {
                assertEquals(2, target.countedOwnerSlotCount());
                target.refCount = 1; // Exercise defensive recovery from a transient undercount.

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    WeakRefRegistry.weaken(weakOwner);

                    assertTrue(WeakRefRegistry.isweak(weakOwner));
                    assertEquals(1, target.refCount);
                    assertEquals(1, target.countedOwnerSlotCount());
                    assertEquals(0, measurement.stats().rootQueries,
                            "the current package element identity proves ownership");
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                RuntimeArray.pop(packageRoot);
                MortalList.flush();
                assertFalse(WeakRefRegistry.isweak(weakOwner),
                        "the final package element release clears the weak observer");
                assertEquals(RuntimeScalarType.UNDEF, weakOwner.type);
            } finally {
                if (WeakRefRegistry.isweak(weakOwner)) WeakRefRegistry.unweaken(weakOwner);
                MyVarCleanupStack.unregister(weakOwner);
                if (!packageRoot.elements.isEmpty()) {
                    RuntimeArray.pop(packageRoot);
                    MortalList.flush();
                }
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }
}
