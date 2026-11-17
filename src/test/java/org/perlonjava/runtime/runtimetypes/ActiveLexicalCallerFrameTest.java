package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ActiveLexicalCallerFrameTest {
    @Test
    void identifiesCallerCellsAcrossRecursiveFramesOfTheSameCode() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode recursiveCode = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeScalar callerCell = new RuntimeScalar(1);
            RuntimeScalar currentCell = new RuntimeScalar(2);

            RuntimeCode.pushActiveCode(recursiveCode);
            RuntimeCode.registerCurrentActiveLexical("$caller", callerCell);
            RuntimeCode.pushActiveCode(recursiveCode);
            RuntimeCode.registerCurrentActiveLexical("$current", currentCell);
            try {
                assertTrue(RuntimeCode.isActiveLexicalCellInCallerFrame(callerCell, recursiveCode));
                assertFalse(RuntimeCode.isActiveLexicalCellInCallerFrame(currentCell, recursiveCode));
            } finally {
                RuntimeCode.popActiveCode(recursiveCode);
                RuntimeCode.popActiveCode(recursiveCode);
            }
        }
    }
}
