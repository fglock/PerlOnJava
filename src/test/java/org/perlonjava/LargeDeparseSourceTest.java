package org.perlonjava;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.backend.bytecode.InterpretedCode;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class LargeDeparseSourceTest {
    @BeforeEach
    void setUp() {
        PerlLanguageProvider.resetAll();
    }

    @Test
    void preservesLargeClosureSourceOnBothBackends() throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.fileName = "<large-source>";
        options.code = "#".repeat(70_000) + "\nsub { 42 };";

        RuntimeList jvmResult = PerlLanguageProvider.executePerlCode(options, false);
        RuntimeCode jvmCode = codeFrom(jvmResult);
        assertFalse(jvmCode instanceof InterpretedCode,
                "large source closure should compile on the JVM backend");

        PerlLanguageProvider.resetAll();
        CompilerOptions interpreterOptions = new CompilerOptions();
        interpreterOptions.fileName = "<large-source-interpreter>";
        interpreterOptions.code = optionsSource();
        interpreterOptions.useInterpreter = true;
        RuntimeList interpreterResult = PerlLanguageProvider.executePerlCode(
                interpreterOptions, false);
        assertInstanceOf(InterpretedCode.class, codeFrom(interpreterResult));
    }

    private static RuntimeCode codeFrom(RuntimeList result) {
        assertEquals(1, result.elements.size());
        assertInstanceOf(RuntimeScalar.class, result.elements.get(0));
        Object value = ((RuntimeScalar) result.elements.get(0)).value;
        assertInstanceOf(RuntimeCode.class, value);
        return (RuntimeCode) value;
    }

    private static String optionsSource() {
        return "#".repeat(70_000) + "\nsub { 42 };";
    }
}
