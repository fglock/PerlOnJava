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

            MortalList.pushMark();
            RuntimeScalar.scopeExitCleanup(durableCell);
            MortalList.popAndFlush();
            assertEquals(Integer.MIN_VALUE, closure.refCount,
                    "the final durable CODE owner retires the closure");
            assertEquals(0, closure.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
        }
    }

    @Test
    void forwardingAnActiveCodeArgumentBorrowsTheCurrentCellOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            closure.refCount = 0;
            closure.capturedScalars = new RuntimeScalar[0];
            RuntimeScalar argumentCell = new RuntimeScalar(closure);
            RuntimeCode.CallArguments outer = RuntimeCode.newCallArguments(1);
            RuntimeCode.CallArguments forwarded = null;
            boolean markActive = false;
            boolean outerArgsActive = false;

            try {
                MortalList.pushMark();
                markActive = true;
                outer.add(argumentCell);
                RuntimeArray outerArgs = outer.toRuntimeArray();
                assertEquals(1, closure.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));

                RuntimeCode.pushArgs(outerArgs);
                outerArgsActive = true;
                forwarded = RuntimeCode.newCallArguments(1);
                forwarded.add(argumentCell);
                RuntimeArray forwardedArgs = forwarded.toRuntimeArray();

                assertFalse(forwardedArgs.ownsElement(argumentCell));
                assertEquals(1, closure.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE),
                        "the forwarded frame borrows its caller's active scalar cell");
                forwarded.finish();
                forwarded = null;

                RuntimeCode.popArgs();
                outerArgsActive = false;
                outer.finish();
                MortalList.popAndFlush();
                markActive = false;

                assertEquals(0, closure.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
            } finally {
                if (forwarded != null) forwarded.finish();
                if (outerArgsActive) RuntimeCode.popArgs();
                outer.finish();
                if (markActive) MortalList.popAndFlush();
            }
        }
    }
}
