package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
class ClearedAggregateRequiresOwnerSlotTest {
    @Test
    void untrackedJavaReferenceDoesNotReplaceAPerlOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeHash referent = new RuntimeHash();
        RuntimeHash packageRoot = new RuntimeHash();
        RuntimeScalar weakObserver = referenceTo(referent);
        RuntimeScalar untrackedElement = referenceTo(referent);
        String globalName =
                "ClearedAggregateRequiresOwnerSlotTest::packageRoot";

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            GlobalVariable.globalHashes.put(globalName, packageRoot);
            // This intentionally bypasses the normal hash store API. The Java
            // reference it creates has no HASH_ELEMENT owner identity.
            packageRoot.elements = new java.util.LinkedHashMap<>();
            packageRoot.elements.put("referent", untrackedElement);
            MyVarCleanupStack.register(referent);
            MortalList.setActive(true);
            try {
                WeakRefRegistry.weaken(weakObserver);
                referent.setTrackedRefCount(0);
                referent.clearedOwnedAggregateElement = true;

                MyVarCleanupStack.unregister(referent);

                assertFalse(DestroyDispatch.hasLivePerlOwnerIdentity(referent));
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        referent.perlLifecycleState());
                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "a Java reference without an owner slot cannot preserve the observer");
            } finally {
                MyVarCleanupStack.unregister(referent);
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                WeakRefRegistry.clearWeakRefsTo(referent);
                GlobalVariable.globalHashes.remove(globalName);
                MortalList.setActive(previousMortalListState);
            }
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.HASHREFERENCE;
        scalar.value = referent;
        return scalar;
    }
}
