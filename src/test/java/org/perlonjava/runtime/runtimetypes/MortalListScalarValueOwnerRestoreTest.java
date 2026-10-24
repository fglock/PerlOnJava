package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class MortalListScalarValueOwnerRestoreTest {
    @Test
    void standaloneScalarSlotRestoresCountWithoutActiveOwnerRegistry() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(referent.createAnonymousReference());
            MyVarCleanupStack.register(owner);

            assertEquals(0, referent.activeOwnerCount(),
                    "the scalar was stored before active-owner tracking existed");
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));

            referent.refCount = 0;
            try {
                assertTrue(MortalList.restoreReachableAggregateOwnerCount(referent),
                        "the explicit scalar slot supplies the lost direct owner");
                assertEquals(1, referent.refCount);
            } finally {
                MyVarCleanupStack.unregister(owner);
                MortalList.deferDecrementIfTracked(owner);
                MortalList.flush();
            }
        }
    }

    @Test
    void deferredReleaseUsesExplicitScalarOwnerAfterLegacyFlagRetires() {
        assertExplicitScalarOwnerRelease(false);
    }

    @Test
    void scopeExitReleasesExplicitScalarOwnerAfterLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(referent.createAnonymousReference());
            MyVarCleanupStack.register(owner);
            int originalRefCount = referent.refCount;
            owner.refCountOwned = false;

            try {
                RuntimeScalar.scopeExitCleanup(owner);
                assertFalse(owner.hasScalarValueOwnerSlotFor(referent),
                        "scope exit retires the explicit scalar slot");
                MyVarCleanupStack.unregister(owner);
                MortalList.flush();
                assertTrue(referent.refCount < originalRefCount,
                        "scope exit queues the explicit owner's decrement");
            } finally {
                MyVarCleanupStack.unregister(owner);
                MortalList.flush();
            }
        }
    }

    @Test
    void scopeExitFastPathRecognizesExplicitGlobOwnerAfterLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeGlob referent = new RuntimeGlob("OwnerSlot::HANDLE");
            referent.refCount = 1;
            RuntimeScalar owner = new RuntimeScalar();
            owner.type = RuntimeScalarType.GLOB;
            owner.value = referent;
            owner.setRefCountOwned(true);
            owner.refCountOwned = false;
            MyVarCleanupStack.register(owner);

            try {
                RuntimeScalar.scopeExitCleanup(owner);
                MortalList.flush();

                assertFalse(owner.scalarValueOwnerSlot().isActive());
                assertTrue(referent.refCount < 1,
                        "scope exit reaches GLOB cleanup through the explicit owner slot");
            } finally {
                MyVarCleanupStack.unregister(owner);
                MortalList.flush();
            }
        }
    }

    @Test
    void explicitUndefReleasesScalarOwnerAfterLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(referent.createAnonymousReference());
            int originalRefCount = referent.refCount;
            owner.refCountOwned = false;

            owner.undefine();

            assertFalse(owner.hasScalarValueOwnerSlotFor(referent),
                    "explicit undef retires the scalar owner slot");
            assertTrue(referent.refCount < originalRefCount,
                    "explicit undef releases the slot's counted owner");
        }
    }

    @Test
    void scalarOverwriteReleasesExplicitOwnerAfterLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray previous = new RuntimeArray();
            RuntimeArray replacement = new RuntimeArray();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(previous.createAnonymousReference());
            int originalRefCount = previous.refCount;
            owner.refCountOwned = false;

            owner.set(replacement.createAnonymousReference());

            assertFalse(owner.hasScalarValueOwnerSlotFor(previous),
                    "overwrite retires the previous scalar owner slot");
            assertTrue(previous.refCount < originalRefCount,
                    "overwrite releases the previous slot's counted owner");
            assertTrue(owner.hasScalarValueOwnerSlotFor(replacement),
                    "overwrite installs the replacement scalar owner slot");
        }
    }

    @Test
    void capturedReleaseUsesExplicitScalarOwnerAfterLegacyFlagRetires() {
        assertExplicitScalarOwnerRelease(true);
    }

    @Test
    void recursiveContainerCleanupUsesExplicitScalarOwnerAfterLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(referent.createAnonymousReference());
            MyVarCleanupStack.register(owner);
            int originalRefCount = referent.refCount;
            owner.refCountOwned = false;

            try {
                MortalList.deferDestroyForContainerClear(java.util.List.of(owner));
                assertFalse(owner.hasScalarValueOwnerSlotFor(referent));
                MyVarCleanupStack.unregister(owner);
                MortalList.flush();
                assertTrue(referent.refCount < originalRefCount,
                        "recursive cleanup queues the explicit owner's decrement");
            } finally {
                MyVarCleanupStack.unregister(owner);
                MortalList.flush();
            }
        }
    }

    @Test
    void tailCallCleanupUsesExplicitScalarOwnerAfterLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(referent.createAnonymousReference());
            RuntimeArray args = new RuntimeArray();
            args.elements.add(owner);
            args.elementsOwned = true;
            args.reconcileArrayElementOwnerSlots();
            int originalRefCount = referent.refCount;
            owner.refCountOwned = false;

            try {
                MortalList.releaseLastOwnedTailCallArgs(args);
                assertFalse(owner.hasScalarValueOwnerSlotFor(referent));
                assertTrue(referent.refCount < originalRefCount,
                        "tail-call cleanup releases the explicit owner's count");
            } finally {
                args.releaseArrayElementOwnerSlots();
                args.elements.clear();
                MortalList.flush();
            }
        }
    }

    @Test
    void tailCallCodeReleaseUsesExplicitScalarOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode code = new RuntimeCode((String) null, null);
            code.refCount = 1;
            RuntimeScalar codeRef = new RuntimeScalar(code);
            codeRef.setRefCountOwned(true);

            try {
                MortalList.releaseTailCallCodeRef(codeRef);

                assertFalse(codeRef.hasScalarValueOwnerSlotFor(code),
                        "tail-call release retires the direct CODE owner slot");
                assertEquals(Integer.MIN_VALUE, code.refCount,
                        "the last tail-call CODE owner reaches the lifecycle dispatcher");
            } finally {
                MortalList.flush();
            }
        }
    }

    @Test
    void voidResultCleanupUsesExplicitScalarOwnerAfterLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(referent.createAnonymousReference());
            RuntimeList result = new RuntimeList(owner);
            int originalRefCount = referent.refCount;
            owner.refCountOwned = false;

            try {
                MortalList.mortalizeForVoidDiscard(result);
                assertFalse(owner.hasScalarValueOwnerSlotFor(referent));
                MortalList.flush();
                assertTrue(referent.refCount < originalRefCount,
                        "void-result cleanup releases the explicit owner's count");
            } finally {
                MortalList.flush();
            }
        }
    }

    private static void assertExplicitScalarOwnerRelease(boolean captured) {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(referent.createAnonymousReference());
            MyVarCleanupStack.register(owner);
            int originalRefCount = referent.refCount;

            owner.refCountOwned = false;
            assertTrue(owner.hasScalarValueOwnerSlotFor(referent));

            try {
                if (captured) {
                    MortalList.releaseCapturedDecrement(owner);
                } else {
                    MortalList.deferDecrementIfTracked(owner);
                }
                assertFalse(owner.hasScalarValueOwnerSlotFor(referent),
                        "deferred cleanup retires the explicit scalar slot");
                MyVarCleanupStack.unregister(owner);
                MortalList.flush();
                assertTrue(referent.refCount < originalRefCount,
                        "the deferred bridge decrement is released");
            } finally {
                MyVarCleanupStack.unregister(owner);
                MortalList.flush();
            }
        }
    }
}
