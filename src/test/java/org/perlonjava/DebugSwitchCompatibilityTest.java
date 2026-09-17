package org.perlonjava;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.ArgumentParser;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.io.StandardIO;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
public class DebugSwitchCompatibilityTest {
    private PrintStream originalErr;
    private ByteArrayOutputStream stderr;
    private RuntimeIO originalStdout;
    private ByteArrayOutputStream stdout;
    private PerlRuntime.Binding runtimeBinding;

    @BeforeEach
    void setUp() throws Exception {
        runtimeBinding = new PerlRuntime().bind();
        PerlLanguageProvider.resetAll();
        originalErr = System.err;
        stderr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
        originalStdout = RuntimeIO.getStdout();
        stdout = new ByteArrayOutputStream();
        RuntimeIO.setStdout(new RuntimeIO(new StandardIO(stdout, true)));
        GlobalVariable.getGlobalIO("main::STDOUT").setIO(RuntimeIO.getStdout());
    }

    @AfterEach
    void tearDown() {
        System.setErr(originalErr);
        RuntimeIO.setStdout(originalStdout);
        GlobalVariable.getGlobalIO("main::STDOUT").setIO(RuntimeIO.getStdout());
        PerlLanguageProvider.resetAll();
        runtimeBinding.close();
    }

    @Test
    void releasePerlDebugSwitchWarnsWithoutEnablingCompilerTrace() {
        CompilerOptions options = ArgumentParser.parseArguments(
                new String[] {"-Dt", "-e", "1"});

        assertEquals("t", options.debugFlags);
        assertFalse(options.debugEnabled);
        assertFalse(CompilerOptions.DEBUG_ENABLED);
        assertEquals(
                "Recompile perl with -DDEBUGGING to use -D switch (did you mean -d ?)\n",
                stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void debuggerGotoKeepsLexicalSubroutineTargetsLive() throws Exception {
        CompilerOptions options = ArgumentParser.parseArguments(new String[] {
                "-d:LexicalSubGoto", "-e",
                "use feature qw(lexical_subs state); "
                        + "no warnings q(experimental::lexical_subs); "
                        + "state sub target { print qq(state\\n) } "
                        + "target(); $^P |= 0x80; sub { goto &target }->();"
        });
        RuntimeArray.push(options.inc, new RuntimeScalar("src/test/resources/unit/lib"));

        PerlLanguageProvider.executePerlCode(options, true);
        RuntimeIO.getStdout().flush();

        assertEquals("state\nstate\n", stdout.toString(StandardCharsets.UTF_8));
    }
}
