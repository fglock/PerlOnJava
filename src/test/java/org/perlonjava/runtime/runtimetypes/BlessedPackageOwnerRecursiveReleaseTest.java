package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class BlessedPackageOwnerRecursiveReleaseTest {
    @Test
    void lastCountedOuterReleaseDoesNotReleaseChildrenOfPackageOwnedParent() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String globalName =
                    "BlessedPackageOwnerRecursiveReleaseTest::packageRoot";
            RuntimeArray packageRoot = new RuntimeArray();
            GlobalVariable.globalArrays.put(globalName, packageRoot);

            RuntimeHash parent = new RuntimeHash();
            parent.blessId = NameNormalizer.getBlessId(
                    "Issue1649::PackageOwnedParent");
            RuntimeScalar packageOwner = parent.createAnonymousReference();
            parent.refCount = -1;
            packageRoot.elements.add(packageOwner);

            RuntimeArray child = new RuntimeArray();
            RuntimeScalar childOwner = child.createAnonymousReference();
            parent.put("child", childOwner);
            RuntimeScalar weakObserver = child.createAnonymousReference();
            WeakRefRegistry.weaken(weakObserver);

            RuntimeHash outer = new RuntimeHash();
            outer.put("parent", parent.createAnonymousReference());

            try {
                assertEquals(1,
                        parent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                assertEquals(1, parent.countedOwnerSlotCount());
                assertTrue(parent.hasPerlStrongOwnerIdentity(),
                        "the package slot proves a live Perl owner before clearing the outer hash");
                assertTrue(WeakRefRegistry.isweak(weakObserver));

                outer.undefine();

                assertEquals(1,
                        parent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE),
                        "the package slot still owns the blessed parent");
                assertEquals(0, parent.countedOwnerSlotCount(),
                        "the outer hash slot was the parent's only counted owner");
                assertTrue(parent.hasPerlStrongOwnerIdentity(),
                        "the package owner remains live after the outer hash is cleared");
                assertNotEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        parent.perlLifecycleState());
                assertTrue(WeakRefRegistry.isweak(weakObserver),
                        "the package-owned parent still owns its nested child");
                assertTrue(weakObserver.value == child,
                        "recursive release must not clear the child's weak observer");

                packageOwner.undefine();

                assertEquals(0,
                        parent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        parent.perlLifecycleState(),
                        "the parent is destroyed when its final package owner leaves");
                assertFalse(WeakRefRegistry.isweak(weakObserver));
                assertEquals(RuntimeScalarType.UNDEF, weakObserver.type,
                        "the child weak observer clears with final parent destruction");
            } finally {
                WeakRefRegistry.clearWeakRefsTo(child);
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }
}
