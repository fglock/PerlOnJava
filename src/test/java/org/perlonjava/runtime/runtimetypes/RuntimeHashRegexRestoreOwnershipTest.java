package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class RuntimeHashRegexRestoreOwnershipTest {
    @Test
    void restoringMutationSnapshotReplacesElementSlotOwnership() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray stableReferent = new RuntimeArray();
            RuntimeArray temporaryReferent = new RuntimeArray();
            RuntimeHash hash = new RuntimeHash();
            hash.elements.put("stable", stableReferent.createReference());

            Object snapshot = hash.snapshotRegexMutationState();
            hash.elements.put("temporary", temporaryReferent.createReference());

            hash.restoreRegexMutationState(snapshot);

            assertEquals(1, stableReferent.hashSlotOwnerCount(),
                    "the restored entry has exactly one hash slot owner");
            assertEquals(0, temporaryReferent.hashSlotOwnerCount(),
                    "the discarded entry no longer has a hash slot owner");
        }
    }
}
