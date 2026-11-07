package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlStrongOwnerProofTest {
    @Test
    void indexesSemanticOwnerSlotsAndExcludesTemporaryRoots() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash target = new RuntimeHash();
            assertFalse(target.hasOwnerSlots());
            assertFalse(target.hasPerlStrongOwnerIdentity());

            PerlOwnerSlot temporaryOwner = new PerlOwnerSlot(PerlOwnerSlot.Kind.TEMPORARY_ROOT);
            temporaryOwner.acquireTemporaryRootIdentity(target);
            try {
                assertFalse(target.hasOwnerSlots(),
                        "temporary roots are not semantic Perl owner slots");
                assertFalse(target.hasPerlStrongOwnerIdentity());
            } finally {
                temporaryOwner.release();
            }

            PerlOwnerSlot first = new PerlOwnerSlot(PerlOwnerSlot.Kind.SCALAR_CAPTURE);
            PerlOwnerSlot second = new PerlOwnerSlot(PerlOwnerSlot.Kind.SCALAR_CAPTURE);
            first.acquireCaptureIdentity(target);
            second.acquireCaptureIdentity(target);
            assertTrue(target.hasOwnerSlots());
            assertTrue(target.hasPerlStrongOwnerIdentity());

            first.release();
            assertTrue(target.hasOwnerSlots(), "one live identity remains");
            assertTrue(target.hasPerlStrongOwnerIdentity());

            second.release();
            assertFalse(target.hasOwnerSlots());
            assertFalse(target.hasPerlStrongOwnerIdentity());
        }
    }

    @Test
    void excludesTemporaryRootsButAcceptsCountedAndUntrackedOwners() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash target = new RuntimeHash();
            assertFalse(target.hasPerlStrongOwner());

            PerlOwnerSlot temporaryOwner = new PerlOwnerSlot(PerlOwnerSlot.Kind.TEMPORARY_ROOT);
            temporaryOwner.acquireTemporaryRootIdentity(target);
            try {
                assertFalse(target.hasPerlStrongOwner(),
                        "a temporary evaluation root is not a Perl owner");
            } finally {
                temporaryOwner.release();
            }

            RuntimeScalar countedOwner = new RuntimeScalar();
            countedOwner.set(target.createAnonymousReference());
            assertTrue(target.hasPerlStrongOwner());
            countedOwner.set(new RuntimeScalar());
            assertFalse(target.hasPerlStrongOwner());

            RuntimeHash untrackedTarget = new RuntimeHash();
            RuntimeArray container = new RuntimeArray();
            RuntimeScalar untrackedOwner = referenceTo(untrackedTarget);
            container.elements.add(untrackedOwner);
            untrackedOwner.markContainerOwner(container);
            untrackedOwner.refreshUntrackedReferenceOwner();
            assertTrue(untrackedTarget.hasPerlStrongOwner());
            untrackedOwner.set(new RuntimeScalar());
            assertFalse(untrackedTarget.hasPerlStrongOwner());
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.REFERENCE;
        scalar.value = referent;
        return scalar;
    }
}
