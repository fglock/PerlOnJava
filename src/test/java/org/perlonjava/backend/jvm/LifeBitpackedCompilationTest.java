package org.perlonjava.backend.jvm;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.PerlRuntimeTestBase;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.backend.bytecode.InterpretedCode;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("unit")
class LifeBitpackedCompilationTest extends PerlRuntimeTestBase {

    @Test
    void flat64GenerationRoutineCompilesOnTheJvmBackend() throws Exception {
        String source = Files.readString(Path.of("examples/life_bitpacked.pl"))
                .replace("my $generations = 5000;", "my $generations = 1;")
                .replace("my $resolution = \"auto\";", "my $resolution = \"none\";");
        CompilerOptions options = new CompilerOptions();
        options.fileName = "examples/life_bitpacked.pl";
        options.code = source;

        PerlLanguageProvider.executePerlCode(options, false);

        RuntimeCode code = materialize("main::next_generation_flat64");
        assertNotNull(code.codeObject);
        assertFalse(code.codeObject instanceof InterpretedCode,
                "the flat64 hot loop must not fall back to the bytecode interpreter");
    }

    private static RuntimeCode materialize(String name) {
        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(name);
        RuntimeCode code = (RuntimeCode) codeRef.value;
        if (code.compilerSupplier != null) code.compilerSupplier.get();
        return code;
    }
}
