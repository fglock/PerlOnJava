package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class ReturnedRvalueCopyTest {

    @Test
    void keepsAnAlreadyDetachedTemporaryAtTheRvalueReturnBoundary() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar temporary = new RuntimeScalar("temporary");
            RuntimeList result = new RuntimeList(temporary);

            RuntimeList returned = RuntimeCode.coerceScalarCallResult(
                    result, RuntimeContextType.LIST, RuntimeContextType.LIST, true);

            assertSame(result, returned);
            assertSame(temporary, returned.getFirst());
        }
    }

    @Test
    void copiesAStillStoredContainerSlotAtTheRvalueReturnBoundary() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray array = new RuntimeArray();
            array.add(new RuntimeScalar("stored"));
            RuntimeScalar stored = array.elements.getFirst();
            RuntimeList result = new RuntimeList(stored);

            RuntimeList returned = RuntimeCode.coerceScalarCallResult(
                    result, RuntimeContextType.LIST, RuntimeContextType.LIST, true);

            assertNotSame(result, returned);
            assertNotSame(stored, returned.getFirst());
        }
    }
}
