package org.perlonjava.app.scriptengine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.backend.bytecode.InterpretedCode;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Regression coverage for dangling ASM label registrations in a labeled loop. */
@Tag("unit")
class LabeledOuterLoopCompilationTest {
    @BeforeEach
    void resetRuntime() {
        PerlLanguageProvider.resetAll();
    }

    @Test
    void callBeforeLastOnOuterLabelRemainsJvmCompiled() throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.fileName = "<labeled-outer-loop>";
        options.code = "sub called { 1 }\n"
                + "sub labeled { OUTER: while (1) { for (1 .. 4) { called(); last OUTER } } return 42 }\n"
                + "labeled()\n";

        try (PerlRuntime.Binding ignored = PerlRuntime.bindCurrentOrNew()) {
            RuntimeList result = PerlLanguageProvider.executePerlCode(options, false);
            assertEquals(42, result.scalar().getInt());

            RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef("main::labeled");
            RuntimeCode code = assertInstanceOf(RuntimeCode.class, codeRef.value);
            assertFalse(code.codeObject instanceof InterpretedCode,
                    "the labeled-loop subroutine must not fall back because of a dangling ASM label");
        }
    }
}
