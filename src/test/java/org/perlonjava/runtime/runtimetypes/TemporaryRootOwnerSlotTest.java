package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryRootOwnerSlotTest {
    @Test
    void duplicateAndNonLifoPopsReleaseTheMatchingRootLeases() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash first = new RuntimeHash();
            RuntimeHash second = new RuntimeHash();

            MortalList.pushTemporaryRoot(first);
            MortalList.pushTemporaryRoot(first);
            MortalList.pushTemporaryRoot(second);

            assertEquals(2, first.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
            assertEquals(1, second.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
            assertEquals(List.of(second, first, first), MortalList.snapshotTemporaryRoots());
            assertTrue(MortalList.temporaryRootDirectlyReferences(first));

            MortalList.popTemporaryRoot(first);
            assertEquals(1, first.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
            assertEquals(List.of(second, first), MortalList.snapshotTemporaryRoots());

            MortalList.popTemporaryRoot(second);
            MortalList.popTemporaryRoot(first);
            assertEquals(0, first.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
            assertEquals(0, second.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
            assertEquals(List.of(), MortalList.snapshotTemporaryRoots());
            assertFalse(MortalList.temporaryRootDirectlyReferences(first));
        }
    }

    @Test
    void depthRestoreReleasesEveryLeaseAddedAfterTheSavedBoundary() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash callerRoot = new RuntimeHash();
            RuntimeHash failedCallRoot = new RuntimeHash();
            MortalList.pushTemporaryRoot(callerRoot);
            int depth = MortalList.temporaryRootDepth();

            try {
                MortalList.pushTemporaryRoot(failedCallRoot);
                MortalList.pushTemporaryRoot(failedCallRoot);
                throw new IllegalStateException("synthetic failed call");
            } catch (IllegalStateException expected) {
                MortalList.restoreTemporaryRootDepth(depth);
            }

            assertEquals(List.of(callerRoot), MortalList.snapshotTemporaryRoots());
            assertEquals(1, callerRoot.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
            assertEquals(0, failedCallRoot.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));

            MortalList.popTemporaryRoot(callerRoot);
            assertEquals(0, callerRoot.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
        }
    }

    @Test
    void clearingRuntimeStateReleasesAllTemporaryRootLeases() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash root = new RuntimeHash();
            MortalList.pushTemporaryRoot(root);
            MortalList.pushTemporaryRoot(root);

            assertEquals(2, root.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
            MortalList.clearTemporaryRoots();
            assertEquals(0, root.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));

            MortalList.pushTemporaryRoot(root);
            MortalList.clearCurrentRuntimeState();

            assertEquals(0, root.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
            assertEquals(List.of(), MortalList.snapshotTemporaryRoots());
        }
    }

    @Test
    void scalarTemporaryRootMatchesOnlyItsDirectStrongReferent() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar reference = referent.createAnonymousReference();
            RuntimeHash unrelated = new RuntimeHash();

            MortalList.pushTemporaryRoot(reference);
            try {
                assertTrue(MortalList.temporaryRootDirectlyReferences(referent));
                assertFalse(MortalList.temporaryRootDirectlyReferences(unrelated));
            } finally {
                MortalList.popTemporaryRoot(reference);
            }
        }
    }
}
