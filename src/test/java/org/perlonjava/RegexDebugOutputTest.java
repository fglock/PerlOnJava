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
public class RegexDebugOutputTest extends PerlRuntimeTestBase {
    private RuntimeIO originalStdout;
    private RuntimeIO originalStderr;
    private ByteArrayOutputStream stdout;
    private ByteArrayOutputStream stderr;

    @BeforeEach
    void setUp() {
        PerlLanguageProvider.resetAll();
        originalStdout = RuntimeIO.getStdout();
        originalStderr = RuntimeIO.getStderr();
        stdout = new ByteArrayOutputStream();
        stderr = new ByteArrayOutputStream();
        RuntimeIO.setStdout(new RuntimeIO(new StandardIO(stdout, true)));
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

    @Test
    void reportsNativeProgramAndCompiledOptimizerAnchor() throws Exception {
        CompilerOptions options = ArgumentParser.parseArguments(new String[] {
                "-e", "use re 'debug'; qr/.*?phase36_debug_label/"
        });

        PerlLanguageProvider.executePerlCode(options, true);
        RuntimeIO.getStdout().flush();
        RuntimeIO.getStderr().flush();
        String trace = stderr.toString(StandardCharsets.ISO_8859_1);

        assertTrue(trace.contains("JONI_PATTERN"), trace);
        assertTrue(trace.contains("anchored(MBOL) implicit"), trace);
        assertFalse(trace.contains("JAVA_PATTERN"), trace);
    }
}
