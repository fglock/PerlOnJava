package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class TransientArgumentAliasOwnerTest {
    @Test
    void stagedCallArgumentRecordsItsTransientScalarCell() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar cell = new RuntimeScalar();
            cell.set(referent.createAnonymousReference());
            RuntimeCode.CallArguments arguments = RuntimeCode.newCallArguments(1);

            try {
                arguments.add(cell);

                assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS),
                        "the staging frame records the scalar cell it retains");
                assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE),
                        "borrowing the cell does not add a referent count");
            } finally {
                arguments.finish();
            }

            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS),
                    "finishing the staging frame retires its alias identity");
        }
    }

    @Test
    void activeArgumentFrameRecordsEveryScalarCell() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar cell = new RuntimeScalar();
            cell.set(referent.createAnonymousReference());
            RuntimeArray arguments = new RuntimeArray();
            arguments.elements.add(cell);
            boolean active = false;

            try {
                RuntimeCode.pushArgs(arguments);
                active = true;

                assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS),
                        "the active @_ frame records its scalar cell even without a lexical marker");
            } finally {
                if (active) RuntimeCode.popArgs();
            }

            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS),
                    "popping @_ retires its alias identity");
        }
    }
}
