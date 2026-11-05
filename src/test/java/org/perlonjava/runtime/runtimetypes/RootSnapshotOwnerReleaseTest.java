package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RootSnapshotOwnerReleaseTest {
    @Test
    void releaseFailureDoesNotSkipLaterSnapshotOwners() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash first = new RuntimeHash();
            RuntimeHash second = new RuntimeHash();
            PerlOwnerSlot laterOwner = temporaryOwner(first, false);
            PerlOwnerSlot failingOwner = temporaryOwner(second, true);
            PerlRuntime.RootSnapshot snapshot = new PerlRuntime.RootSnapshot(
                    runtime, List.of(first, second), List.of(failingOwner, laterOwner));

            try {
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        snapshot::close);

                assertEquals("owner release failed", failure.getMessage());
                assertFalse(failingOwner.isActive());
                assertFalse(laterOwner.isActive(), "later owners are still retired after failure");
                assertEquals(0, first.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
                assertEquals(0, second.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
            } finally {
                failingOwner.release();
                laterOwner.release();
            }
        }
        runtime.close();
    }

    @Test
    void runtimeCloseAttemptsEverySnapshotAfterOneReleaseFails() {
        PerlRuntime runtime = new PerlRuntime();
        PerlRuntime.RootSnapshot firstSnapshot;
        PerlRuntime.RootSnapshot secondSnapshot;
        PerlOwnerSlot firstOwner;
        PerlOwnerSlot secondOwner;
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash first = new RuntimeHash();
            RuntimeHash second = new RuntimeHash();
            firstOwner = temporaryOwner(first, true);
            secondOwner = temporaryOwner(second, true);
            firstSnapshot = new PerlRuntime.RootSnapshot(
                    runtime, List.of(first), List.of(firstOwner));
            secondSnapshot = new PerlRuntime.RootSnapshot(
                    runtime, List.of(second), List.of(secondOwner));
        }

        try {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, runtime::close);

            assertEquals("owner release failed", failure.getMessage());
            assertFalse(firstOwner.isActive());
            assertFalse(secondOwner.isActive());
            assertTrue(runtime.isClosed(), "runtime teardown completes after all releases are tried");
        } finally {
            firstOwner.release();
            secondOwner.release();
            firstSnapshot.close();
            secondSnapshot.close();
            runtime.close();
        }
    }

    private static PerlOwnerSlot temporaryOwner(RuntimeBase referent, boolean failAfterRelease) {
        AtomicBoolean shouldFail = new AtomicBoolean(failAfterRelease);
        PerlOwnerSlot owner = new PerlOwnerSlot(PerlOwnerSlot.Kind.TEMPORARY_ROOT) {
            @Override
            synchronized void release() {
                super.release();
                if (shouldFail.compareAndSet(true, false)) {
                    throw new IllegalStateException("owner release failed");
                }
            }
        };
        owner.acquireTemporaryRootIdentity(referent);
        return owner;
    }
}
