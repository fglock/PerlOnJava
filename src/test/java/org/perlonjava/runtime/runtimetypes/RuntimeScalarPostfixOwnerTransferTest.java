package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeScalarPostfixOwnerTransferTest {
    @Test
    void postfixIncrementMovesAndThenReleasesTheScalarValueSlot() {
        assertPostfixOwnerTransfer(true);
    }

    @Test
    void postfixDecrementMovesAndThenReleasesTheScalarValueSlot() {
        assertPostfixOwnerTransfer(false);
    }

    private static void assertPostfixOwnerTransfer(boolean increment) {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar source = new RuntimeScalar();
            source.set(referent.createAnonymousReference());
            PerlOwnerSlot originalSlot = source.scalarValueOwnerSlot();
            assertNotNull(originalSlot);
            assertEquals(1, referent.refCount);

            RuntimeScalar postfix = increment
                    ? source.postAutoIncrement()
                    : source.postAutoDecrement();

            assertNull(source.scalarValueOwnerSlot(),
                    "the lvalue no longer owns the old reference after postfix mutation");
            assertTrue(originalSlot.isActive());
            assertEquals(originalSlot.identity(), postfix.scalarValueOwnerSlot().identity(),
                    "the returned scalar inherits the same owner-slot identity");
            assertEquals(1, referent.refCount);

            RuntimeScalar destination = new RuntimeScalar();
            postfix.addToScalar(destination);

            assertFalse(originalSlot.isActive(),
                    "assignment consumes the postfix temporary owner");
            assertTrue(destination.scalarValueOwnerSlot().isActive());
            assertFalse(postfix.scalarValueOwnerSlot().isActive(),
                    "the consumed slot remains attached only as an inactive reusable token");
            assertEquals(1, referent.refCount);
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE),
                    "one counted owner has exactly one live scalar-value slot");
        }
    }
}
