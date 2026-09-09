package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ArgumentFrameSnapshotReuseTest {

    @Test
    void recycledSnapshotCannotReactivateAnOldArgumentCopy() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeScalar first = new RuntimeScalar("first");
        RuntimeScalar second = new RuntimeScalar("second");

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray firstArgs = new RuntimeArray();
            firstArgs.add(first);
            RuntimeScalar firstArgument = firstArgs.elements.getFirst();
            RuntimeCode.pushArgs(firstArgs);
            RuntimeArray.shift(firstArgs);
            Object firstToken = RuntimeCode.currentArgumentAliasFrame(firstArgument);
            assertTrue(RuntimeCode.isArgumentFrameActive(firstToken));
            RuntimeCode.popArgs();
            assertFalse(RuntimeCode.isArgumentFrameActive(firstToken));

            RuntimeArray secondArgs = new RuntimeArray();
            secondArgs.add(second);
            RuntimeScalar secondArgument = secondArgs.elements.getFirst();
            RuntimeCode.pushArgs(secondArgs);
            RuntimeArray.shift(secondArgs);
            Object secondToken = RuntimeCode.currentArgumentAliasFrame(secondArgument);
            assertNotSame(firstToken, secondToken);
            assertFalse(RuntimeCode.isArgumentFrameActive(firstToken));
            assertTrue(RuntimeCode.isArgumentFrameActive(secondToken));
            RuntimeCode.popArgs();
        }
    }
}
