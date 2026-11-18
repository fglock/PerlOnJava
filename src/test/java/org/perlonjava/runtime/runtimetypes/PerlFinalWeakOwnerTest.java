package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PerlFinalWeakOwnerTest {
    @Test
    void returnOwnerAcquiresTheScalarCellBeforeItsLexicalBindingExits() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash contents = new RuntimeHash();
            RuntimeScalar cell = new RuntimeScalar();
            MyVarCleanupStack.register(cell);
            cell.set(contents.createAnonymousReference());
            RuntimeBase returned = RuntimeCode.prepareScopeReturn(
                    cell.createReference(), RuntimeContextType.SCALAR, true);
            assertEquals(1, cell.refCount);
            MortalList.deferDecrementIfNotCaptured(cell);
            MyVarCleanupStack.unregister(cell);
            assertFalse(cell.localBindingExists);
            assertTrue(cell.refCountOwned);
            assertEquals(1, contents.refCount);
            RuntimeScalar receiver = new RuntimeScalar();
            receiver.set(returned.scalar());
            assertEquals(1, cell.refCount);
            assertSame(cell, receiver.value);
            cell.set(new RuntimeScalar());
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, contents.perlLifecycleState());
            receiver.set(new RuntimeScalar());
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, cell.perlLifecycleState());
        }
    }

    @Test
    void finalNestedArraySlotOverwriteReleasesItsObserver() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode.pushArgs(new RuntimeArray());
            RuntimeCode.pushArgs(new RuntimeArray());
            try {
                RuntimeHash referent = new RuntimeHash();
                referent.blessId = NameNormalizer.getBlessId("FinalWeakOwner::NoDestroy");
                RuntimeArray array = new RuntimeArray();
                array.push(referent.createAnonymousReference());
                RuntimeScalar element = array.elements.getFirst();
                RuntimeScalar observer = new RuntimeScalar();
                observer.set(element);
                WeakRefRegistry.weaken(observer);
                PerlOwnerSlot slot = element.countedOwnerSlot();
                {
                    element.set(new RuntimeScalar());
                    assertEquals(RuntimeScalarType.UNDEF, observer.type);
                    assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, referent.perlLifecycleState());
                    assertFalse(slot.isActive());
                }
            } finally {
                RuntimeCode.popArgs();
                RuntimeCode.popArgs();
            }
        }
    }

    @Test
    void weakeningTheLastIdentifiedOwnerClearsItsObserver() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(referent.createAnonymousReference());
            PerlOwnerSlot slot = owner.countedOwnerSlot();
            {
                WeakRefRegistry.weaken(owner);
                assertEquals(RuntimeScalarType.UNDEF, owner.type);
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, referent.perlLifecycleState());
                assertFalse(slot.isActive());
            }
        }
    }

    @Test
    void weakeningOneLexicalOwnerTrustsAnotherExplicitOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.blessId = NameNormalizer.getBlessId("FinalWeakOwner::Shared");
            RuntimeScalar observer = referent.createAnonymousReference();
            WeakRefRegistry.weaken(observer);

            RuntimeScalar weakenedOwner = new RuntimeScalar();
            RuntimeScalar survivingOwner = new RuntimeScalar();
            MyVarCleanupStack.register(weakenedOwner);
            MyVarCleanupStack.register(survivingOwner);
            weakenedOwner.set(referent.createAnonymousReference());
            survivingOwner.set(referent.createAnonymousReference());

            try {
                assertEquals(2, referent.countedOwnerSlotCount());
                {
                    WeakRefRegistry.weaken(weakenedOwner);

                    assertTrue(WeakRefRegistry.isweak(observer));
                    assertTrue(WeakRefRegistry.isweak(weakenedOwner));
                    assertEquals(1, referent.countedOwnerSlotCount());
                }
            } finally {
                if (WeakRefRegistry.isweak(weakenedOwner)) {
                    WeakRefRegistry.unweaken(weakenedOwner);
                }
                if (WeakRefRegistry.isweak(observer)) WeakRefRegistry.unweaken(observer);
                MyVarCleanupStack.unregister(weakenedOwner);
                MyVarCleanupStack.unregister(survivingOwner);
                weakenedOwner.set(new RuntimeScalar());
                survivingOwner.set(new RuntimeScalar());
                observer.set(new RuntimeScalar());
            }
        }
    }

    @Test
    void scalarCellsReleaseTheirWeakObserversWhenTheLexicalOwnerExits() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar cell = new RuntimeScalar(42);
            MyVarCleanupStack.register(cell);
            RuntimeScalar observer = new RuntimeScalar();
            observer.set(cell.createReference());
            WeakRefRegistry.weaken(observer);
            assertEquals(0, cell.refCount);
            assertTrue(cell.localBindingExists);
            assertEquals(RuntimeScalarType.REFERENCE, observer.type);
            RuntimeScalar.scopeExitCleanup(cell);
            MyVarCleanupStack.unregister(cell);
            assertFalse(cell.localBindingExists);
            assertEquals(RuntimeScalarType.UNDEF, observer.type);
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, cell.perlLifecycleState());
            RuntimeScalar.scopeExitCleanup(cell);
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, cell.perlLifecycleState());
        }
    }

    @Test
    void escapedReferenceOutlivesTheLexicalBindingAndOwnsTheCellsContents() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash contents = new RuntimeHash();
            RuntimeScalar cell = new RuntimeScalar();
            cell.set(contents.createAnonymousReference());
            MyVarCleanupStack.register(cell);
            RuntimeScalar escaped = new RuntimeScalar();
            escaped.set(cell.createReference());
            RuntimeScalar observer = new RuntimeScalar();
            observer.set(escaped);
            WeakRefRegistry.weaken(observer);
            RuntimeScalar.scopeExitCleanup(cell);
            MyVarCleanupStack.unregister(cell);
            assertEquals(RuntimeScalarType.REFERENCE, observer.type);
            assertTrue(cell.refCountOwned);
            assertEquals(1, cell.refCount);
            assertEquals(1, contents.refCount);
            escaped.set(new RuntimeScalar());
            assertEquals(RuntimeScalarType.UNDEF, observer.type);
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, cell.perlLifecycleState());
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, contents.perlLifecycleState());
        }
    }
}
