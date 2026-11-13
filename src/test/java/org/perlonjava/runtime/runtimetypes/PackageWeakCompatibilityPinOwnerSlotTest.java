package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PackageWeakCompatibilityPinOwnerSlotTest {
    @Test
    void packageWeakObserverFollowsTheFinalUntrackedScalarOwner() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeArray packageRoot = new RuntimeArray();
            String globalName = "PackageWeakCompatibilityPinOwnerSlotTest::packageRoot";
            GlobalVariable.globalArrays.put(globalName, packageRoot);

            RuntimeArray target = new RuntimeArray();
            RuntimeScalar firstOwner = target.createAnonymousReference();
            target.refCount = -1;
            packageRoot.elements.add(firstOwner);
            RuntimeScalar secondOwner = target.createAnonymousReference();
            target.refCount = -1;
            packageRoot.elements.add(secondOwner);
            RuntimeScalar weakObserver = target.createAnonymousReference();
            target.refCount = -1;

            try {
                WeakRefRegistry.weaken(weakObserver);

                assertEquals(WeakRefRegistry.WEAKLY_TRACKED, target.refCount,
                        "weak tracking does not manufacture a positive count");
                assertEquals(2, target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                firstOwner.undefine();
                assertTrue(WeakRefRegistry.isweak(weakObserver));
                assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                secondOwner.undefine();
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, target.perlLifecycleState());
                assertEquals(0, target.refCount);
                assertFalse(WeakRefRegistry.isweak(weakObserver));
                assertFalse(target.hasOwnerSlots());
                secondOwner.undefine();
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, target.perlLifecycleState(),
                        "repeated owner release cannot revive or destroy the referent twice");
                assertEquals(0, target.refCount);
            } finally {
                WeakRefRegistry.clearWeakRefsTo(target);
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }

    @Test
    void unweakenedReferenceBecomesAnOrdinaryUntrackedOwner() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeArray packageRoot = new RuntimeArray();
            String globalName = "PackageWeakCompatibilityPinOwnerSlotTest::unweakenedRoot";
            GlobalVariable.globalArrays.put(globalName, packageRoot);

            RuntimeArray target = new RuntimeArray();
            RuntimeScalar firstOwner = target.createAnonymousReference();
            target.refCount = -1;
            packageRoot.elements.add(firstOwner);
            RuntimeScalar secondOwner = target.createAnonymousReference();
            target.refCount = -1;
            packageRoot.elements.add(secondOwner);
            RuntimeScalar observer = target.createAnonymousReference();
            target.refCount = -1;

            try {
                WeakRefRegistry.weaken(observer);
                MyVarCleanupStack.register(observer);
                WeakRefRegistry.unweaken(observer);

                assertFalse(WeakRefRegistry.isweak(observer));
                assertEquals(3,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE),
                        "unweakening installs the observer's direct strong owner slot");

                firstOwner.undefine();
                assertTrue(observer.value == target,
                        "the unweakened observer remains a strong referent");
                assertEquals(2,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                secondOwner.undefine();
                assertTrue(observer.value == target,
                        "the observer alone now owns the referent");
                assertEquals(1,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                observer.undefine();
                assertFalse(target.hasOwnerSlots(),
                        "undef retires the final direct untracked owner");
            } finally {
                MyVarCleanupStack.unregister(observer);
                WeakRefRegistry.clearWeakRefsTo(target);
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }
}
