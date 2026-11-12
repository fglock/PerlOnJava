package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TransientAggregateHoldIsNotDurableOwnerTest {
    @Test
    void stagedArgumentContainerDoesNotMakeAnUnownedArrayDurable() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeArray temporary = new RuntimeArray();
            RuntimeScalar cell = referent.createAnonymousReference();
            temporary.elements.add(cell);

            assertFalse(cell.hasLiveDurableStrongValueSlot());
            RuntimeCode.CallArguments staged = RuntimeCode.newCallArguments(1);
            try {
                staged.add(cell);
                assertTrue(temporary.hasPerlStrongOwner(),
                        "staging temporarily keeps the untracked array available");
                assertFalse(temporary.hasPersistentPerlStrongOwnerIdentity(),
                        "the staging lease is not durable array storage");
                assertFalse(cell.hasLiveDurableStrongValueSlot(),
                        "a temporary argument-container lease cannot keep the cell durable");
            } finally {
                staged.finish();
            }
        }
    }

    @Test
    void durableArrayOwnerSurvivesATemporaryContainerHint() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeArray persistent = new RuntimeArray();
            persistent.localBindingExists = true;
            RuntimeScalar cell = referent.createAnonymousReference();
            persistent.elements.add(cell);
            RuntimeArray temporary = new RuntimeArray();
            temporary.elements.add(cell);

            assertTrue(cell.hasLiveDurableStrongValueSlot(),
                    "all current containers are considered, not only the latest hint");
        }
    }

    @Test
    void stagedArgumentContainerDoesNotMakeAnUnownedHashDurable() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeHash temporary = new RuntimeHash();
            RuntimeScalar cell = referent.createAnonymousReference();
            temporary.elements.put("cell", cell);

            assertFalse(cell.hasLiveDurableStrongValueSlot());
            RuntimeCode.CallArguments staged = RuntimeCode.newCallArguments(1);
            try {
                staged.add(cell);
                assertTrue(temporary.hasPerlStrongOwner());
                assertFalse(temporary.hasPersistentPerlStrongOwnerIdentity());
                assertFalse(cell.hasLiveDurableStrongValueSlot());
            } finally {
                staged.finish();
            }
        }
    }

    @Test
    void durableHashOwnerSurvivesATemporaryContainerHint() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeHash persistent = new RuntimeHash();
            persistent.localBindingExists = true;
            RuntimeScalar cell = referent.createAnonymousReference();
            persistent.elements.put("cell", cell);
            RuntimeHash temporary = new RuntimeHash();
            temporary.elements.put("cell", cell);

            assertTrue(cell.hasLiveDurableStrongValueSlot(),
                    "all current containers are considered, not only the latest hint");
        }
    }

    @Test
    void untrackedClosureInTemporaryArrayDoesNotBlockCaptureRetirement() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            closure.refCount = -1;
            closure.capturedScalars = new RuntimeScalar[0];
            RuntimeScalar codeCell = new RuntimeScalar(closure);
            RuntimeArray temporary = new RuntimeArray();
            temporary.elements.add(codeCell);

            assertTrue(closure.hasUntrackedReferenceOwnerSlotIdentity());
            assertFalse(closure.hasPerlOwnerForCaptureOtherThan(null),
                    "an unowned aggregate cell is not a durable capture owner");
            closure.requestCaptureReleaseWhenUnowned();

            assertNull(closure.capturedScalars,
                    "the stale untracked scalar edge must not keep captures alive");
        }
    }

    @Test
    void durableArrayCellKeepsClosureCapturesAlive() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            closure.refCount = -1;
            closure.capturedScalars = new RuntimeScalar[0];
            RuntimeScalar codeCell = new RuntimeScalar(closure);
            RuntimeArray persistent = new RuntimeArray();
            persistent.localBindingExists = true;
            persistent.elements.add(codeCell);

            assertTrue(closure.hasPerlOwnerForCaptureOtherThan(null));
            closure.requestCaptureReleaseWhenUnowned();

            assertTrue(closure.capturedScalars != null,
                    "a durable scalar cell must continue to retain its closure captures");
        }
    }
}
