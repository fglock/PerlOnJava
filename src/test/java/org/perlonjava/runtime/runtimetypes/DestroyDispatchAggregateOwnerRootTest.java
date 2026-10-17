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
            PerlOwnerSlot slot = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.HASH_ENTRY, null, localHash);
            slot.acquireHashEntry(referent);

            assertFalse(hasNewRootedOwner(referent),
                    "a new hash entry in an unrooted temporary hash is not resurrection");
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
            PerlOwnerSlot slot = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.HASH_ENTRY, null, globalHash);
            slot.acquireHashEntry(referent);
            try {
                assertTrue(hasNewRootedOwner(referent),
                        "a new hash entry in a package global hash is resurrection");
            } finally {
                GlobalVariable.globalHashes.remove(globalName);
                slot.release();
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
