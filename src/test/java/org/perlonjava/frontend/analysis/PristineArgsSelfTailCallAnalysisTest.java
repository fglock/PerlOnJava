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
class PristineArgsSelfTailCallAnalysisTest extends PerlRuntimeTestBase {

    @Test
    void onlyDirectSelfTailCallsCanShareThePristineArgumentList() throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.fileName = "pristine_args_self_tailcall.t";
        options.code = "package PristineArgsSelfTailCall;"
                + "sub safe { my ($self) = @_; goto &safe if $self; return $self; }"
                + "sub other { shift @_; }"
                + "sub unsafe { goto &other; }"
                + "safe(0); unsafe(1); 1;";
        RuntimeArray.push(options.inc, new RuntimeScalar("src/main/perl/lib"));
        PerlLanguageProvider.executePerlCode(options, false);

        RuntimeCode safe = materializeNamedCode(
                "PristineArgsSelfTailCall::safe");
        RuntimeCode unsafe = materializeNamedCode(
                "PristineArgsSelfTailCall::unsafe");
        assertFalse(safe.requiresPristineArgsSnapshot());
        assertTrue(unsafe.requiresPristineArgsSnapshot());
    }

    @Test
    void nativeFrameMetadataKeepsReentrantWeakeningConservative() throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.fileName = "native_call_frame_metadata.t";
        options.code = "package NativeCallFrameMetadata; use Time::HiRes; "
                + "use Scalar::Util; 1;";
        RuntimeArray.push(options.inc, new RuntimeScalar("src/main/perl/lib"));
        PerlLanguageProvider.executePerlCode(options, false);

        RuntimeCode time = materializeNamedCode("Time::HiRes::time");
        RuntimeCode clockGettime = materializeNamedCode("Time::HiRes::clock_gettime");
        RuntimeCode weaken = materializeNamedCode("Scalar::Util::weaken");
        assertFalse(time.requiresActiveLexicalFrame());
        assertFalse(clockGettime.requiresActiveLexicalFrame());
        assertFalse(weaken.requiresActiveLexicalFrame());
        assertFalse(time.requiresCallDepthTracking());
        assertTrue(clockGettime.requiresCallDepthTracking(),
                "clock_gettime can fetch tied scalars or invoke numeric overload code");
        assertTrue(weaken.requiresCallDepthTracking(),
                "weaken can trigger user DESTROY code");
    }

    private static RuntimeCode materializeNamedCode(String name) {
        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(name);
        RuntimeCode code = (RuntimeCode) codeRef.value;
        if (code.compilerSupplier != null) code.compilerSupplier.get();
        return (RuntimeCode) codeRef.value;
    }
}
