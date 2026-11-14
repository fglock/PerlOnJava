package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class FirstWeakenUsesCurrentPackageOwnerTest {
    @Test
    void weakeningLocalOwnerPreservesThePackageElementOwner() {
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
                assertEquals(2, target.refCount);

                {
                    WeakRefRegistry.weaken(weakOwner);

                    assertTrue(WeakRefRegistry.isweak(weakOwner));
                    assertEquals(1, target.refCount);
                    assertEquals(1, target.countedOwnerSlotCount());
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
