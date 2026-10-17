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
