package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class MutableIntegerPayloadTest {
    @Test
    void arithmeticPayloadDoesNotAliasCopiesOrLocalizedState() {
        PerlRuntime runtime = new PerlRuntime().initialize();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar value = new RuntimeScalar(1000);
            value.setArithmeticIntegerResult(1001, false);
            RuntimeScalar copied = new RuntimeScalar(value);
            RuntimeScalar assigned = new RuntimeScalar();
            assigned.set(value);

            value.setArithmeticIntegerResult(1002, false);
            assertEquals(1001L, copied.getLong());
            assertEquals(1001L, assigned.getLong());

            value.dynamicSaveState();
            value.setArithmeticIntegerResult(1003, false);
            value.dynamicRestoreState();
            assertEquals(1002L, value.getLong());
        } finally {
            runtime.close();
        }
    }
}
