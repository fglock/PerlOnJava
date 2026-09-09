package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class ActiveLexicalFrameReuseTest {

    @Test
    void releasedFrameIsReusedWithoutLeakingLexicalCells() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeCode outer = new RuntimeCode("outer", java.util.List.of());
        RuntimeCode inner = new RuntimeCode("inner", java.util.List.of());
        RuntimeCode next = new RuntimeCode("next", java.util.List.of());

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode.pushActiveCode(outer);
            outer.resolveLexicalAlias("$outer", new RuntimeScalar("outer"));
            RuntimeCode.pushActiveCode(inner);
            inner.resolveLexicalAlias("$inner", new RuntimeScalar("inner"));

            assertEquals("outer", RuntimeCode.snapshotActiveLexicals(outer)
                    .get("$outer").toString());
            assertEquals("inner", RuntimeCode.snapshotActiveLexicals(inner)
                    .get("$inner").toString());

            RuntimeCode.popActiveCode(inner);
            RuntimeCode.popActiveCode(outer);
            RuntimeCode.ActiveLexicalFrame released =
                    runtime.executionState().availableActiveLexicalFrames.peekFirst();

            RuntimeCode.pushActiveCode(next);
            assertSame(released, runtime.executionState().activeLexicalFrames.peekFirst());
            assertTrueEmpty(RuntimeCode.snapshotActiveLexicals(next));
            RuntimeCode.popActiveCode(next);
        }
    }

    private static void assertTrueEmpty(java.util.Map<String, RuntimeBase> values) {
        assertFalse(values.containsKey("$outer"));
        assertFalse(values.containsKey("$inner"));
    }
}
