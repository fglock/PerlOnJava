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
