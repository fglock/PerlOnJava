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

/** Regression coverage for class-file UTF-8 constant overflow in large modules. */
@Tag("unit")
class LargeDeparseSourceCompilationTest {
    @BeforeEach
    void resetRuntime() {
        PerlLanguageProvider.resetAll();
    }

    @Test
    void largeSourceKeepsNamedSubroutineOnJvmBackend() throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.fileName = "<large-deparse-source>";
        options.code = "sub giant { 42 }\n#" + "x".repeat(70_000) + "\ngiant()\n";

        try (PerlRuntime.Binding ignored = PerlRuntime.bindCurrentOrNew()) {
            RuntimeList result = PerlLanguageProvider.executePerlCode(options, false);
            assertEquals(42, result.scalar().getInt());

            RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef("main::giant");
            RuntimeCode code = assertInstanceOf(RuntimeCode.class, codeRef.value);
            assertFalse(code.codeObject instanceof InterpretedCode,
                    "large deparse source must not force JVM compilation fallback");
        }
    }
}
