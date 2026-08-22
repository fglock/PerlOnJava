package org.perlonjava;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.ArgumentParser;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.io.StandardIO;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;

@Tag("unit")
public class RegexDebugTraceContractTest extends PerlRuntimeTestBase {
    private RuntimeIO originalStdout;
    private RuntimeIO originalStderr;
    private ByteArrayOutputStream stderr;

    @BeforeEach
    void setUp() {
        PerlLanguageProvider.resetAll();
        originalStdout = RuntimeIO.getStdout();
        originalStderr = RuntimeIO.getStderr();
        installCapturedIo();
    }

    @AfterEach
    void tearDown() {
        RuntimeIO.setStdout(originalStdout);
        RuntimeIO.setStderr(originalStderr);
        GlobalVariable.getGlobalIO("main::STDOUT").setIO(RuntimeIO.getStdout());
        GlobalVariable.getGlobalIO("main::STDERR").setIO(RuntimeIO.getStderr());
        PerlLanguageProvider.resetAll();
    }

    private void installCapturedIo() {
        RuntimeIO.setStdout(new RuntimeIO(
                new StandardIO(new ByteArrayOutputStream(), true)));
        stderr = new ByteArrayOutputStream();
        RuntimeIO.setStderr(new RuntimeIO(new StandardIO(stderr, false)));
        GlobalVariable.getGlobalIO("main::STDOUT").setIO(RuntimeIO.getStdout());
        GlobalVariable.getGlobalIO("main::STDERR").setIO(RuntimeIO.getStderr());
    }

    private String execute(String source) throws Exception {
        CompilerOptions options = ArgumentParser.parseArguments(
                new String[] {"-e", source});
        PerlLanguageProvider.executePerlCode(options, true);
        RuntimeIO.getStderr().flush();
        return stderr.toString(StandardCharsets.ISO_8859_1);
    }

    @Test
    void selectsCompileAndExecuteFamiliesAndLifecycle() throws Exception {
        String trace = execute("{ use re Debug => 'COMPILE'; "
                + "qr/phase36_trace_compile/; } "
                + "{ use re Debug => 'EXECUTE'; "
                + "'phase36_trace_execute' =~ /phase36_trace_execute/; }");

        assertTrue(trace.contains("Compiling REx \"phase36_trace_compile\""), trace);
        assertTrue(trace.contains("Final program:"), trace);
        assertTrue(trace.contains("JONI_PATTERN native bytecode:"), trace);
        assertTrue(trace.contains("code length:"), trace);
        assertFalse(trace.contains("Matching REx \"phase36_trace_compile\""), trace);
        assertFalse(trace.contains("Compiling REx \"phase36_trace_execute\""), trace);
        assertTrue(trace.contains("Matching REx \"phase36_trace_execute\""), trace);
        assertTrue(trace.contains("Freeing REx: \"phase36_trace_compile\""), trace);
        assertTrue(trace.contains("Freeing REx: \"phase36_trace_execute\""), trace);
    }

    @Test
    void preservesLexicalDisableEvalRestorationAndColor() throws Exception {
        String trace = execute("{ use re 'debug'; qr/phase36_trace_outer/; "
                + "{ no re 'debug'; qr/phase36_trace_muted/; } "
                + "eval q{qr/phase36_trace_eval/}; } "
                + "qr/phase36_trace_outside/; "
                + "{ use re 'debugcolor'; qr/phase36_trace_color/; "
                + "{ no re 'debugcolor'; qr/phase36_trace_color_muted/; } }");

        assertTrue(trace.contains("Compiling REx \"phase36_trace_outer\""), trace);
        assertTrue(trace.contains("Compiling REx \"phase36_trace_eval\""), trace);
        assertFalse(trace.contains("phase36_trace_muted"), trace);
        assertFalse(trace.contains("phase36_trace_outside"), trace);
        assertTrue(trace.contains("\u001b[36mCompiling REx \"phase36_trace_color\""), trace);
        assertFalse(trace.contains("phase36_trace_color_muted"), trace);
    }

    @Test
    void tracesRuntimeSourceAndSubstitutionExecution() throws Exception {
        String trace = execute("use re 'eval'; "
                + "{ use re Debug => 'EXECUTE'; "
                + "my $p = '(?{ 1 })phase36_runtime_source'; "
                + "'phase36_runtime_source' =~ /$p/; "
                + "my $s = 'phase36_substitution'; "
                + "$s =~ s/phase36_substitution/replaced/; }");

        assertTrue(trace.contains("Matching REx \"(?{ 1 })phase36_runtime_source\""), trace);
        assertTrue(trace.contains("Matching REx \"phase36_substitution\""), trace);
    }

    @Test
    void warnsForUnknownNamedDebugFlag() throws Exception {
        String trace = execute("use re Debug => 'BOGUS'; qr/phase36_unknown_debug/;");
        assertTrue(trace.contains("Unknown \"re\" Debug flag 'BOGUS'"), trace);
        assertFalse(trace.contains("Compiling REx \"phase36_unknown_debug\""), trace);
    }

    @Test
    void independentRuntimesEachEmitCompileAndFreeLifecycle() throws Exception {
        String source = "use re Debug => 'COMPILE'; qr/phase36_runtime_lifecycle/;";
        String first = execute(source);
        assertTrue(first.contains("Compiling REx \"phase36_runtime_lifecycle\""), first);
        assertTrue(first.contains("Freeing REx: \"phase36_runtime_lifecycle\""), first);

        PerlLanguageProvider.resetAll();
        installCapturedIo();
        String second = execute(source);
        assertTrue(second.contains("Compiling REx \"phase36_runtime_lifecycle\""), second);
        assertTrue(second.contains("Freeing REx: \"phase36_runtime_lifecycle\""), second);
    }
}
