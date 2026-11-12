package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                assertTrue(temporary.hasCurrentPerlStorageOwner(),
                        "a live argument-container transfer remains a current storage owner");
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
            RuntimeScalar capturedPad = new RuntimeScalar(42);
            PerlOwnerSlot captureOwner = capturedPad.retainClosureCaptureSlot(closure);
            closure.capturedScalars = new RuntimeScalar[] {capturedPad};
            closure.capturedScalarOwnerSlots = new PerlOwnerSlot[] {captureOwner};
            RuntimeScalar codeCell = new RuntimeScalar(closure);
            RuntimeArray temporary = new RuntimeArray();
            temporary.localBindingExists = true;
            temporary.elements.add(codeCell);
            temporary.localBindingExists = false;

            assertEquals(1, closure.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
            assertFalse(closure.hasPerlOwnerForCaptureOtherThan(null),
                    "an unowned aggregate cell is not a durable capture owner");
            closure.requestCaptureReleaseWhenUnowned();

            assertNull(closure.capturedScalars,
                    "the stale untracked scalar edge must not keep captures alive");
            assertFalse(captureOwner.isActive());
        }
    }

    @Test
    void activeAggregateTransferKeepsNestedClosureCapturesAlive() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            closure.refCount = -1;
            RuntimeScalar capturedPad = new RuntimeScalar(42);
            PerlOwnerSlot captureOwner = capturedPad.retainClosureCaptureSlot(closure);
            closure.capturedScalars = new RuntimeScalar[] {capturedPad};
            closure.capturedScalarOwnerSlots = new PerlOwnerSlot[] {captureOwner};
            RuntimeScalar codeCell = new RuntimeScalar(closure);
            RuntimeArray temporary = new RuntimeArray();
            temporary.localBindingExists = true;
            temporary.elements.add(codeCell);
            temporary.localBindingExists = false;
            RuntimeCode.CallArguments staged = RuntimeCode.newCallArguments(1);

            MortalList.pushMark();
            boolean markActive = true;
            try {
                staged.add(codeCell);

                assertTrue(temporary.hasCurrentPerlStorageOwner());
                assertFalse(temporary.hasPersistentPerlStrongOwnerIdentity());
                assertFalse(closure.hasCallTransferOwner(),
                        "only the containing aggregate is transferred as an argument");
                assertTrue(closure.hasPerlOwnerForCaptureOtherThan(null),
                        "the nested callback remains live through its aggregate's call transfer");

                RuntimeCode.pushActiveCode(closure);
                closure.requestCaptureReleaseWhenUnowned();
                assertTrue(captureOwner.isActive());

                staged.finish();
                MortalList.popAndFlush();
                markActive = false;
                RuntimeCode.popActiveCode(closure);

                assertNull(closure.capturedScalars,
                        "the requested release completes after both owners leave");
                assertFalse(captureOwner.isActive());
            } finally {
                staged.finish();
                if (markActive) MortalList.popAndFlush();
                if (RuntimeCode.isActiveCode(closure)) RuntimeCode.popActiveCode(closure);
            }
        }
    }

    @Test
    void durableArrayCellKeepsClosureCapturesAlive() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            closure.refCount = -1;
            RuntimeScalar capturedPad = new RuntimeScalar(42);
            PerlOwnerSlot captureOwner = capturedPad.retainClosureCaptureSlot(closure);
            closure.capturedScalars = new RuntimeScalar[] {capturedPad};
            closure.capturedScalarOwnerSlots = new PerlOwnerSlot[] {captureOwner};
            RuntimeScalar codeCell = new RuntimeScalar(closure);
            RuntimeArray persistent = new RuntimeArray();
            persistent.localBindingExists = true;
            persistent.elements.add(codeCell);

            assertTrue(closure.hasPerlOwnerForCaptureOtherThan(null));
            closure.requestCaptureReleaseWhenUnowned();

            assertTrue(closure.capturedScalars != null,
                    "a durable scalar cell must continue to retain its closure captures");
            assertTrue(captureOwner.isActive());
        }
    }

    @Test
    void activeCallTransferKeepsTemporaryClosureCapturesAlive() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            closure.refCount = 0;
            RuntimeScalar capturedPad = new RuntimeScalar(42);
            PerlOwnerSlot captureOwner = capturedPad.retainClosureCaptureSlot(closure);
            closure.capturedScalars = new RuntimeScalar[] {capturedPad};
            closure.capturedScalarOwnerSlots = new PerlOwnerSlot[] {captureOwner};
            RuntimeCode.CallArguments staged = RuntimeCode.newCallArguments(1);

            MortalList.pushMark();
            boolean markActive = true;
            try {
                staged.add(new RuntimeScalar(closure));
                staged.toRuntimeArray();

                assertTrue(closure.hasCallTransferOwner());
                closure.requestCaptureReleaseWhenUnowned();
                assertTrue(closure.capturedScalars != null,
                        "an active call transfer still needs the closure's captures");
                assertTrue(captureOwner.isActive());

                staged.finish();
                MortalList.popAndFlush();
                markActive = false;

                assertNull(closure.capturedScalars,
                        "the requested capture release completes when the transfer ends");
                assertFalse(captureOwner.isActive());
            } finally {
                staged.finish();
                if (markActive) MortalList.popAndFlush();
            }
        }
    }
}
