package org.perlonjava.backend.bytecode;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class InterpretedCodeClosureMetadataTest {

    @Test
    void closureCopyRetainsIndependentCleanupRegisterMetadata() {
        int[] bytecode = {Opcodes.SCOPE_EXIT_CLEANUP, 4};
        InterpretedCode template = new InterpretedCode(
                bytecode, new Object[0], new String[0], 8, null,
                "test", 1, null, null, null, 0, 0, null);

        InterpretedCode closure = template.withCapturedVars(null);

        assertTrue(closure.myVarRegisters.get(4));
        closure.myVarRegisters.clear(4);
        assertTrue(template.myVarRegisters.get(4));
        assertFalse(closure.myVarRegisters.get(4));
    }
}
