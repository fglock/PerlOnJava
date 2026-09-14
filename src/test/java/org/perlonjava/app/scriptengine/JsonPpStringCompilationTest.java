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

/** Regression coverage for JSON::PP's hot string parser staying on the JVM backend. */
@Tag("unit")
class JsonPpStringCompilationTest {
    @BeforeEach
    void resetRuntime() {
        PerlLanguageProvider.resetAll();
    }

    @Test
    void jsonStringParserRemainsJvmCompiled() throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.fileName = "<json-pp-string>";
        options.code = "use JSON::PP; my $json = JSON::PP->new; $json->decode('{\"a\":\"x\"}')->{a}\n";

        try (PerlRuntime.Binding ignored = PerlRuntime.bindCurrentOrNew()) {
            RuntimeList result = PerlLanguageProvider.executePerlCode(options, false);
            assertEquals("x", result.scalar().toString());

            RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef("JSON::PP::_string");
            RuntimeCode code = assertInstanceOf(RuntimeCode.class, codeRef.value);
            assertFalse(code.codeObject instanceof InterpretedCode,
                    "JSON::PP::_string must not fall back to the bytecode interpreter");
        }
    }
}
