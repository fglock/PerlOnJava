package org.perlonjava.runtime.operators;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.GlobalContext;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class SystemOperatorCurrentJperlPathTest {
    @Test
    void readsTheEncodedSpecialVariableUsedByDollarCaretX() {
        PerlRuntime runtime = new PerlRuntime().initialize();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String launcher = "C:\\regex implementation worktree\\jperl.bat";
            GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("X"))
                    .set(launcher);

            assertEquals(launcher, SystemOperator.getCurrentJperlPath());
        }
    }
}
