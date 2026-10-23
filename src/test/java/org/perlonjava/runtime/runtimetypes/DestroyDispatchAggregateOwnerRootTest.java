package org.perlonjava.runtime.runtimetypes;

import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class DestroyDispatchAggregateOwnerRootTest {
    @Test
    void destructorLocalScalarOwnerDoesNotCountAsResurrection() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            referent.activateOwnerTracking();
            RuntimeScalar localOwner = new RuntimeScalar();
            localOwner.set(referent.createReference());
            MyVarCleanupStack.register(localOwner);
            try {
                assertFalse(hasNewRootedOwner(referent),
                        "a new scalar owner in an active local frame is not global resurrection");
            } finally {
                MyVarCleanupStack.unregister(localOwner);
            }
        }
    }

    @Test
    void scalarOwnerInPackageGlobalHashCountsAsResurrection() {
        PerlRuntime runtime = new PerlRuntime();
        String globalName = "DestroyDispatchAggregateOwnerRootTest::scalar_owner";
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            referent.activateOwnerTracking();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(referent.createReference());
            RuntimeHash globalHash = new RuntimeHash();
            globalHash.isGlobalPackageHash = true;
            GlobalVariable.globalHashes.put(globalName, globalHash);
            globalHash.elements.put("owner", owner);
            try {
                assertTrue(hasNewRootedOwner(referent),
                        "a new scalar owner reachable through a package-global hash is resurrection");
            } finally {
                globalHash.elements.clear();
                GlobalVariable.globalHashes.remove(globalName);
            }
        }
    }

    @Test
    void scalarOwnerInsideDyingReferentDoesNotCountAsResurrection() {
        PerlRuntime runtime = new PerlRuntime();
        String rootName = "DestroyDispatchAggregateOwnerRootTest::self_root";
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.activateOwnerTracking();
            GlobalVariable.globalHashes.put(rootName, referent);
            RuntimeScalar selfOwner = referent.createReference();
            referent.put("self", selfOwner);
            try {
                assertFalse(hasNewRootedOwner(referent),
                        "a new scalar owner reached only through the dying object is not resurrection");
            } finally {
                referent.elements.clear();
                referent.releaseHashEntryOwnerSlots();
                GlobalVariable.globalHashes.remove(rootName);
            }
        }
    }

    @Test
    void aggregateOwnerContainerInsideDyingReferentDoesNotCountAsResurrection() {
        PerlRuntime runtime = new PerlRuntime();
        String rootName = "DestroyDispatchAggregateOwnerRootTest::aggregate_self_root";
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeHash ownerContainer = new RuntimeHash();
            referent.activateOwnerTracking();
            GlobalVariable.globalHashes.put(rootName, referent);
            ownerContainer.put("self", referent.createReference());
            referent.put("container", ownerContainer.createReference());
            try {
                assertFalse(hasNewRootedOwner(referent),
                        "an owner container reached only through the dying object is not resurrection");
            } finally {
                ownerContainer.elements.clear();
                ownerContainer.releaseHashEntryOwnerSlots();
                referent.elements.clear();
                referent.releaseHashEntryOwnerSlots();
                GlobalVariable.globalHashes.remove(rootName);
            }
        }
    }

    @Test
    void legacyClosurePadReachableOnlyThroughDyingReferentDoesNotCountAsResurrection() {
        PerlRuntime runtime = new PerlRuntime();
        String rootName = "DestroyDispatchAggregateOwnerRootTest::closure_self_root";
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.activateOwnerTracking();
            GlobalVariable.globalHashes.put(rootName, referent);
            RuntimeScalar padCell = referent.createReference();
            referent.put("pad", padCell);
            PerlOwnerSlot slot = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.CLOSURE_PAD, padCell);
            slot.acquireCapture(referent);
            try {
                assertFalse(hasNewRootedOwner(referent),
                        "a legacy closure pad reachable only through the dying object is not resurrection");
            } finally {
                slot.release();
                referent.elements.clear();
                referent.releaseHashEntryOwnerSlots();
                GlobalVariable.globalHashes.remove(rootName);
            }
        }
    }

    @Test
    void incompleteSmallRootBudgetDoesNotTreatNestedArrayOwnerAsResurrection() {
        PerlRuntime runtime = new PerlRuntime();
        String referentRoot = "DestroyDispatchAggregateOwnerRootTest::large_self_root";
        String paddingRoot = "DestroyDispatchAggregateOwnerRootTest::large_padding";
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeArray ownerArray = new RuntimeArray();
            RuntimeArray padding = new RuntimeArray(60_000);
            referent.activateOwnerTracking();
            GlobalVariable.globalHashes.put(referentRoot, referent);
            GlobalVariable.globalArrays.put(paddingRoot, padding);
            for (int i = 0; i < 60_000; i++) {
                padding.elements.add(new RuntimeScalar(i));
            }
            ownerArray.push(referent.createReference());
            referent.put("owner", ownerArray.createReference());
            try {
                assertFalse(hasNewRootedOwner(referent),
                        "a capped walk across unrelated package roots must not invent resurrection through the dying referent");
            } finally {
                ownerArray.elements.clear();
                ownerArray.releaseArrayElementOwnerSlots();
                referent.elements.clear();
                referent.releaseHashEntryOwnerSlots();
                GlobalVariable.globalHashes.remove(referentRoot);
                GlobalVariable.globalArrays.remove(paddingRoot);
            }
        }
    }

    @Test
    void captureFromUnrootedClosureDoesNotCountAsResurrection() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeCode temporaryClosure = new RuntimeCode(null, java.util.List.of());
            PerlOwnerSlot slot = new PerlOwnerSlot(PerlOwnerSlot.Kind.CLOSURE_PAD);
            slot.acquireCapture(referent, temporaryClosure);
            try {
                assertFalse(hasNewRootedOwner(referent),
                        "a capture owned only by an unrooted closure is not resurrection");
            } finally {
                slot.release();
            }
        }
    }

    @Test
    void captureFromPackageGlobalClosureCountsAsResurrection() {
        PerlRuntime runtime = new PerlRuntime();
        String globalName = "DestroyDispatchAggregateOwnerRootTest::closure";
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeCode rootedClosure = new RuntimeCode(null, java.util.List.of());
            GlobalVariable.globalCodeRefs.put(globalName, new RuntimeScalar(rootedClosure));
            PerlOwnerSlot slot = new PerlOwnerSlot(PerlOwnerSlot.Kind.CLOSURE_PAD);
            slot.acquireCapture(referent, rootedClosure);
            try {
                assertTrue(hasNewRootedOwner(referent),
                        "a capture owned by a package-global closure is resurrection");
            } finally {
                GlobalVariable.globalCodeRefs.remove(globalName);
                slot.release();
            }
        }
    }

    @Test
    void legacyCapturePadCountsOnlyWhenReachableThroughRootedCode() {
        PerlRuntime runtime = new PerlRuntime();
        String globalName = "DestroyDispatchAggregateOwnerRootTest::capturing_closure";
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray temporaryReferent = new RuntimeArray();
            RuntimeScalar temporaryPad = temporaryReferent.createReference();
            PerlOwnerSlot temporarySlot = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.CLOSURE_PAD, temporaryPad);
            temporarySlot.acquireCapture(temporaryReferent);
            assertFalse(hasNewRootedOwner(temporaryReferent),
                    "a pad retained only by an unrooted code object is not resurrection");
            temporarySlot.release();

            RuntimeArray rootedReferent = new RuntimeArray();
            RuntimeScalar rootedPad = rootedReferent.createReference();
            RuntimeCode rootedClosure = new RuntimeCode(null, java.util.List.of());
            rootedClosure.capturedScalars = new RuntimeScalar[] {rootedPad};
            GlobalVariable.globalCodeRefs.put(globalName, new RuntimeScalar(rootedClosure));
            PerlOwnerSlot rootedSlot = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.CLOSURE_PAD, rootedPad);
            rootedSlot.acquireCapture(rootedReferent);
            try {
                assertTrue(hasNewRootedOwner(rootedReferent),
                        "a pad captured by package-global code is resurrection");
            } finally {
                GlobalVariable.globalCodeRefs.remove(globalName);
                rootedSlot.release();
            }
        }
    }

    @Test
    void destructorLocalHashEntryDoesNotCountAsResurrection() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash localHash = new RuntimeHash();
            RuntimeArray referent = new RuntimeArray();
            localHash.elements.put("self", referent.createReference());
            try {
                assertFalse(hasNewRootedOwner(referent),
                        "a new hash entry in an unrooted temporary hash is not resurrection");
            } finally {
                localHash.elements.clear();
            }
        }
    }

    @Test
    void hashEntryInPackageGlobalHashCountsAsResurrection() {
        PerlRuntime runtime = new PerlRuntime();
        String globalName = "DestroyDispatchAggregateOwnerRootTest::global";
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash globalHash = new RuntimeHash();
            globalHash.isGlobalPackageHash = true;
            GlobalVariable.globalHashes.put(globalName, globalHash);
            RuntimeArray referent = new RuntimeArray();
            globalHash.elements.put("self", referent.createReference());
            try {
                assertTrue(hasNewRootedOwner(referent),
                        "a new hash entry in a package global hash is resurrection");
            } finally {
                globalHash.elements.clear();
                GlobalVariable.globalHashes.remove(globalName);
            }
        }
    }

    private static boolean hasNewRootedOwner(RuntimeBase referent) {
        Set<RuntimeScalar> noScalarsAtStart = Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        Set<PerlOwnerSlot> noSlotsAtStart = Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        return DestroyDispatch.hasEscapedPerlOwner(
                referent, true, noScalarsAtStart, noSlotsAtStart);
    }
}
