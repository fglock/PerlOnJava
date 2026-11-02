package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class EndBlockOwnerIdentityTest {
    @Test
    void queuedAndExecutingEndBlockKeepExactOwnersWithoutInventingACount() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar capturedPad = new RuntimeScalar(42);
            AtomicReference<RuntimeCode> codeRef = new AtomicReference<>();
            AtomicInteger callbackActiveOwnerCount = new AtomicInteger();
            AtomicBoolean callbackSawCapture = new AtomicBoolean();
            RuntimeCode code = new RuntimeCode((args, context) -> {
                RuntimeCode activeCode = codeRef.get();
                callbackActiveOwnerCount.set(
                        activeCode.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_CODE));
                callbackSawCapture.set(activeCode.capturedScalars != null
                        && activeCode.capturedScalars.length == 1
                        && activeCode.capturedScalars[0] == capturedPad);
                return new RuntimeList();
            }, null);
            codeRef.set(code);
            code.refCount = 0;
            code.capturedScalars = new RuntimeScalar[]{capturedPad};
            code.capturedScalarOwnerSlots = new PerlOwnerSlot[]{
                    capturedPad.retainClosureCaptureSlot()};

            RuntimeScalar lexicalOwner = new RuntimeScalar();
            MyVarCleanupStack.register(lexicalOwner);
            try {
                lexicalOwner.set(new RuntimeScalar(code));
                SpecialBlock.saveEndBlock(new RuntimeScalar(code));

                assertEquals(2, code.refCount);
                assertEquals(1, code.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
                assertEquals(1, code.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
                assertTrue(code.restoreVerifiedCurrentCountedOwnerSlotRefCount());

                // Simulate the selective count reaching its destroyed sentinel
                // while these two current storage slots still own the CV.
                code.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(code);

                assertEquals(2, code.refCount,
                        "restore the two current owners instead of pinning refCount to one");
                assertEquals(1, capturedPad.captureCount,
                        "the queued END block continues to own its captured pad");

                SpecialBlock.runEndBlocks();
                MortalList.flushAboveMark();

                assertEquals(2, callbackActiveOwnerCount.get(),
                        "the handoff identity and active frame span dequeue through invocation");
                assertTrue(callbackSawCapture.get(),
                        "the capture remains live while the END block executes");
                assertEquals(1, code.refCount,
                        "dequeue releases exactly the END queue's counted edge");
                assertEquals(0, code.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
                assertEquals(1, code.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));

                lexicalOwner.set(new RuntimeScalar());
                assertEquals(0, capturedPad.captureCount,
                        "the lexical's final release retires the captured pad");
                assertFalse(code.hasActiveCodeOwner());
            } finally {
                while (!SpecialBlock.getEndBlocks().isEmpty()) {
                    RuntimeArray.shift(SpecialBlock.getEndBlocks());
                }
                MortalList.flush();
                lexicalOwner.set(new RuntimeScalar());
                MyVarCleanupStack.unregister(lexicalOwner);
                MortalList.flushAboveMark();
            }
        }
    }
}
