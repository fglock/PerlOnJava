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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PristineArgsInterpreterFallbackMetadataTest extends PerlRuntimeTestBase {

    @Test
    void interpreterCodeKeepsThePristineArgumentAnalysisResult() throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.useInterpreter = true;
        options.fileName = "pristine_args_interpreter_metadata.t";
        options.code = "package PristineArgsInterpreterMetadata; "
                + "sub safe { my ($self) = @_; return unless $self; "
                + "goto FINISH; FINISH: goto &safe if 0; return $self; } "
                + "sub mutating { my ($self) = @_; shift @_; return $self; } 1;";
        PerlLanguageProvider.executePerlCode(options, false);

        RuntimeCode safe = materialize("PristineArgsInterpreterMetadata::safe");
        RuntimeCode mutating = materialize("PristineArgsInterpreterMetadata::mutating");

        assertInstanceOf(InterpretedCode.class, safe.subroutine);
        assertFalse(safe.requiresPristineArgsSnapshot());
        assertInstanceOf(InterpretedCode.class, mutating.subroutine);
        assertTrue(mutating.requiresPristineArgsSnapshot());
    }

    private static RuntimeCode materialize(String name) {
        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(name);
        RuntimeCode code = (RuntimeCode) codeRef.value;
        if (code.compilerSupplier != null) code.compilerSupplier.get();
        return code;
    }
}
