package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryCodeArgumentOwnerIdentityTest {
    @Test
    void temporaryCodeCellGetsArgumentOwnerWhenAnotherCellOwnsTheCode() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            closure.refCount = 0;
            closure.capturedScalars = new RuntimeScalar[0];

            RuntimeScalar durableCell = new RuntimeScalar();
            durableCell.localBindingExists = true;
            durableCell.set(new RuntimeScalar(closure));
            assertEquals(1, closure.refCount);
            assertNotNull(durableCell.countedOwnerSlot());

            RuntimeScalar temporaryCell = new RuntimeScalar(closure);
            assertFalse(temporaryCell.hasPersistentArgumentStorageCell());
            RuntimeCode.CallArguments staged = RuntimeCode.newCallArguments(1);

            MortalList.pushMark();
            boolean markActive = true;
            try {
                staged.add(temporaryCell);
                RuntimeArray arguments = staged.toRuntimeArray();

                assertTrue(arguments.ownsElement(temporaryCell));
                assertEquals(1, closure.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE),
                        "the temporary cell needs an edge even when another cell owns the CODE");
                assertEquals(2, closure.refCount);
                assertEquals(PerlOwnerSlot.Kind.ARGUMENT_VALUE,
                        temporaryCell.countedOwnerSlot().kind());

                staged.finish();
                MortalList.popAndFlush();
                markActive = false;

                assertFalse(arguments.ownsElement(temporaryCell));
                assertEquals(0, closure.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
                assertEquals(1, closure.refCount,
                        "argument cleanup preserves the distinct durable CODE cell");
                assertTrue(closure.capturedScalars != null,
                        "the remaining cell still owns the closure captures");
            } finally {
                staged.finish();
                if (markActive) MortalList.popAndFlush();
            }

            RuntimeScalar.scopeExitCleanup(durableCell);
            MortalList.flushAboveMark();
            assertEquals(0, closure.refCount);
            assertEquals(0, closure.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
        }
    }
}
