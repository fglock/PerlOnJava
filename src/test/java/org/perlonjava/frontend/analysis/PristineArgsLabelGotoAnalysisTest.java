package org.perlonjava.frontend.analysis;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.PerlRuntimeTestBase;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PristineArgsLabelGotoAnalysisTest extends PerlRuntimeTestBase {

    @Test
    void staticLabelGotoCanSharePristineArgsButMutationsStillRequireSnapshot() throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.fileName = "pristine_args_label_goto.t";
        options.code = "package PristineArgsLabelGoto;"
                + "sub safe { my ($self) = @_; goto LABEL if $self; return; "
                + "LABEL: goto &safe if $self; return $self; }"
                + "sub unsafe { my ($self) = @_; goto LABEL; LABEL: shift @_; }"
                + "safe(0); unsafe(1); 1;";
        RuntimeArray.push(options.inc, new RuntimeScalar("src/main/perl/lib"));
        PerlLanguageProvider.executePerlCode(options, false);

        RuntimeCode safe = materializeNamedCode("PristineArgsLabelGoto::safe");
        RuntimeCode unsafe = materializeNamedCode("PristineArgsLabelGoto::unsafe");
        assertFalse(safe.requiresPristineArgsSnapshot());
        assertTrue(unsafe.requiresPristineArgsSnapshot());
    }

    private static RuntimeCode materializeNamedCode(String name) {
        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(name);
        RuntimeCode code = (RuntimeCode) codeRef.value;
        if (code.compilerSupplier != null) code.compilerSupplier.get();
        return (RuntimeCode) codeRef.value;
    }
}
