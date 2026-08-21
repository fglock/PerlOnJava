package org.perlonjava;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
class RegexFailedCompileDebugLifecycleTest extends PerlRuntimeTestBase {
    private RuntimeIO originalStdout;
    private RuntimeIO originalStderr;
    private ByteArrayOutputStream stderr;

    private record FailedCompile(String trace, String diagnostic) {}

    @BeforeEach
    void setUp() {
        PerlLanguageProvider.resetAll();
        originalStdout = RuntimeIO.getStdout();
        originalStderr = RuntimeIO.getStderr();
        RuntimeIO.setStdout(new RuntimeIO(
                new StandardIO(new ByteArrayOutputStream(), true)));
        stderr = new ByteArrayOutputStream();
        RuntimeIO.setStderr(new RuntimeIO(new StandardIO(stderr, false)));
        GlobalVariable.getGlobalIO("main::STDOUT").setIO(RuntimeIO.getStdout());
        GlobalVariable.getGlobalIO("main::STDERR").setIO(RuntimeIO.getStderr());
    }

    @AfterEach
    void tearDown() {
        RuntimeIO.setStdout(originalStdout);
        RuntimeIO.setStderr(originalStderr);
        GlobalVariable.getGlobalIO("main::STDOUT").setIO(RuntimeIO.getStdout());
        GlobalVariable.getGlobalIO("main::STDERR").setIO(RuntimeIO.getStderr());
        PerlLanguageProvider.resetAll();
    }

    private FailedCompile compileFailure(String source) throws Exception {
        CompilerOptions options = ArgumentParser.parseArguments(
                new String[] {"-e", source});
        String diagnostic;
        try {
            PerlLanguageProvider.executePerlCode(options, true);
            fail("expected regex compilation to fail");
            return null;
        } catch (RuntimeException exception) {
            diagnostic = exception.getMessage();
        }
        RuntimeIO.getStderr().flush();
        return new FailedCompile(
                stderr.toString(StandardCharsets.ISO_8859_1), diagnostic);
    }

    @Test
    void retainsNativeFactsAndLifecycleForFailedTemplates() throws Exception {
        FailedCompile ordinary = compileFailure(
                "use re Debug => 'PARSE'; qr{(?<b>\\g{c}}");
        assertTrue(ordinary.trace.contains("Assembling pattern from 1 elements"),
                ordinary.trace);
        assertTrue(ordinary.trace.contains("Compiling REx \"(?<b>\\g{c}\""),
                ordinary.trace);
        assertTrue(ordinary.trace.contains("Starting parse and generation"),
                ordinary.trace);
        assertTrue(ordinary.trace.contains("Freeing REx: \"(?<b>\\g{c}\""),
                ordinary.trace);
        assertTrue(ordinary.diagnostic.startsWith("Unmatched ("),
                ordinary.diagnostic);

        PerlLanguageProvider.resetAll();
        stderr.reset();
        FailedCompile callback = compileFailure(
                "use re Debug => 'ALL'; qr{(?{a})(?<b>\\g{c}}");
        assertTrue(callback.trace.contains("Assembling pattern from 2 elements"),
                callback.trace);
        assertTrue(callback.trace.contains(
                "Compiling REx \"(?{a})(?<b>\\g{c}\""), callback.trace);
    }
}
