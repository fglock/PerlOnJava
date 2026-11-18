package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

            assertEquals(2, first.ownerSlotCount(PerlOwnerSlot.Kind.EXPRESSION_VALUE));
            assertEquals(1, second.ownerSlotCount(PerlOwnerSlot.Kind.EXPRESSION_VALUE));
            assertEquals(3, MortalList.temporaryRootDepth());

            MortalList.popTemporaryRoot(first);
            assertEquals(1, first.ownerSlotCount(PerlOwnerSlot.Kind.EXPRESSION_VALUE));
            assertEquals(2, MortalList.temporaryRootDepth());

            MortalList.popTemporaryRoot(second);
            MortalList.popTemporaryRoot(first);
            assertEquals(0, first.ownerSlotCount(PerlOwnerSlot.Kind.EXPRESSION_VALUE));
            assertEquals(0, second.ownerSlotCount(PerlOwnerSlot.Kind.EXPRESSION_VALUE));
            assertEquals(0, MortalList.temporaryRootDepth());
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

            assertEquals(depth, MortalList.temporaryRootDepth());
            assertEquals(1, callerRoot.ownerSlotCount(
                    PerlOwnerSlot.Kind.EXPRESSION_VALUE));
            assertEquals(0, failedCallRoot.ownerSlotCount(
                    PerlOwnerSlot.Kind.EXPRESSION_VALUE));

            MortalList.popTemporaryRoot(callerRoot);
            assertEquals(0, callerRoot.ownerSlotCount(
                    PerlOwnerSlot.Kind.EXPRESSION_VALUE));
        }
    }

    @Test
    void clearingRuntimeStateReleasesAllTemporaryRootLeases() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash root = new RuntimeHash();
            root.setTrackedRefCount(1); // Keep one external owner while checking runtime reset.
            MortalList.pushTemporaryRoot(root);
            MortalList.pushTemporaryRoot(root);

            assertEquals(2, root.ownerSlotCount(
                    PerlOwnerSlot.Kind.EXPRESSION_VALUE));
            MortalList.clearTemporaryRoots();
            assertEquals(1, root.refCount);
            assertEquals(0, root.ownerSlotCount(
                    PerlOwnerSlot.Kind.EXPRESSION_VALUE));

            MortalList.pushTemporaryRoot(root);
            MortalList.clearCurrentRuntimeState();

            assertEquals(1, root.refCount);
            assertEquals(0, root.ownerSlotCount(
                    PerlOwnerSlot.Kind.EXPRESSION_VALUE));
            assertEquals(0, MortalList.temporaryRootDepth());
        }
    }

    @Test
    void scalarTemporaryRootOwnsTheScalarValue() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar reference = referent.createAnonymousReference();
            RuntimeHash unrelated = new RuntimeHash();

            MortalList.pushTemporaryRoot(reference);
            try {
                assertEquals(1, reference.ownerSlotCount(
                        PerlOwnerSlot.Kind.EXPRESSION_VALUE));
                assertEquals(referent, reference.value);
                assertEquals(0, unrelated.ownerSlotCount(
                        PerlOwnerSlot.Kind.EXPRESSION_VALUE));
            } finally {
                MortalList.popTemporaryRoot(reference);
            }
        }
    }

    @Test
    void arrayChildOwnershipUsesItsArrayElementSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar reference = referent.createAnonymousReference();
            RuntimeArray root = new RuntimeArray();
            root.addClonedElement(reference);

            MortalList.pushTemporaryRoot(root);
            try {
                assertEquals(1, root.ownerSlotCount(
                        PerlOwnerSlot.Kind.EXPRESSION_VALUE));
                assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
            } finally {
                MortalList.popTemporaryRoot(root);
            }
        }
    }

    @Test
    void hashChildOwnershipUsesItsHashElementSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeHash root = new RuntimeHash();
            root.put("child", referent.createAnonymousReference());

            MortalList.pushTemporaryRoot(root);
            try {
                assertEquals(1, root.ownerSlotCount(
                        PerlOwnerSlot.Kind.EXPRESSION_VALUE));
                assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));
            } finally {
                MortalList.popTemporaryRoot(root);
            }
        }
    }
}
