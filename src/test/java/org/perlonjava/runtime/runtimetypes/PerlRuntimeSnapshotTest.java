package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlRuntimeSnapshotTest {

    @Test
    void snapshotClonesGlobalsRunsHooksAndStartsWithFreshExecutionState() throws Exception {
        for (boolean interpreter : new boolean[]{false, true}) {
            PerlRuntime parent = new PerlRuntime();
            run(parent, interpreter, ""
                    + "package Hook; our $cloned = 0; sub CLONE { ++$cloned } "
                    + "package Skip; sub CLONE_SKIP { 1 } "
                    + "package main; our $graph = { value => 40 }; "
                    + "our $alias = $graph; our $skipped = bless({}, 'Skip'); 1");

            PerlRuntime child = parent.snapshotClone();

            assertTrue(child.isInitialized());
            assertNotSame(parent.globalState(), child.globalState());
            assertEquals("0:40:Skip", run(parent, interpreter,
                    "join q(:), $Hook::cloned, $graph->{value}, ref($skipped)"));
            assertEquals("1:41:41:", run(child, interpreter,
                    "$graph->{value}++; join q(:), $Hook::cloned, "
                            + "$graph->{value}, $alias->{value}, ref($skipped)"));
            assertEquals("0:40:40", run(parent, interpreter,
                    "join q(:), $Hook::cloned, $graph->{value}, $alias->{value}"));
            assertEquals(0, child.executionState().argsStack.size());
            assertEquals(0, child.executionState().callerStack.size());
        }
    }

    private static String run(PerlRuntime runtime, boolean interpreter, String source)
            throws Exception {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            CompilerOptions options = new CompilerOptions();
            options.fileName = "<runtime-snapshot>";
            options.useInterpreter = interpreter;
            options.code = source;
            return PerlLanguageProvider.executePerlCode(options, false).scalar().toString();
        }
    }
}
