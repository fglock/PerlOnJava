package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PackageWeakCompatibilityPinOwnerSlotTest {
    @Test
    void packageWeakPinHasIdentityAndReleasesAfterTheFinalScalarOwner() {
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

                PerlOwnerSlot compatibilityPin = target.packageRootRescueOwnerSlot;
                assertTrue(target.packageRootRescuedUntrackedAggregate);
                assertTrue(compatibilityPin.isActive());
                assertEquals(1, target.ownerSlotCount(
                        PerlOwnerSlot.Kind.WEAKLY_TRACKED_COMPATIBILITY_PIN));
                assertEquals(2, target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                firstOwner.undefine();
                assertTrue(WeakRefRegistry.isweak(weakObserver));
                assertTrue(compatibilityPin.isActive(),
                        "the bridge remains while a scalar owner slot remains");

                secondOwner.undefine();
                assertFalse(compatibilityPin.isActive(),
                        "the compatibility owner identity releases exactly once");
                assertFalse(target.packageRootRescuedUntrackedAggregate);
                assertEquals(0, target.ownerSlotCount(
                        PerlOwnerSlot.Kind.WEAKLY_TRACKED_COMPATIBILITY_PIN));
                assertEquals(Integer.MIN_VALUE, target.refCount);
                assertFalse(WeakRefRegistry.isweak(weakObserver));

                compatibilityPin.release();
                assertEquals(Integer.MIN_VALUE, target.refCount,
                        "repeated slot release cannot revive or release the referent twice");
            } finally {
                WeakRefRegistry.clearWeakRefsTo(target);
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    @Test
    void unweakenedReferenceKeepsItsCountWhenPackageOwnersLeave() {
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
                PerlOwnerSlot compatibilityPin = target.packageRootRescueOwnerSlot;
                WeakRefRegistry.unweaken(observer);

                assertEquals(2, target.refCount,
                        "the strong observer count includes its owner and the compatibility pin");
                assertTrue(compatibilityPin.isActive());

                firstOwner.undefine();
                secondOwner.undefine();

                assertFalse(compatibilityPin.isActive(),
                        "the final package slot retires only the compatibility count");
                assertEquals(1, target.refCount,
                        "the unweakened scalar's real owner count remains");
                assertEquals(0, target.ownerSlotCount(
                        PerlOwnerSlot.Kind.WEAKLY_TRACKED_COMPATIBILITY_PIN));

                observer.undefine();
                assertEquals(Integer.MIN_VALUE, target.refCount,
                        "the unweakened scalar releases the remaining real count");
            } finally {
                WeakRefRegistry.clearWeakRefsTo(target);
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }
}
