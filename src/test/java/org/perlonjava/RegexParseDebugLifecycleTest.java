package org.perlonjava;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class RegexParseDebugLifecycleTest extends PerlRuntimeTestBase {
    private RuntimeIO originalStdout;
    private RuntimeIO originalStderr;
    private ByteArrayOutputStream stderr;

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

    private String execute(String source) throws Exception {
        CompilerOptions options = ArgumentParser.parseArguments(
                new String[] {"-e", source});
        PerlLanguageProvider.executePerlCode(options, true);
        RuntimeIO.getStderr().flush();
        return stderr.toString(StandardCharsets.ISO_8859_1);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int offset = 0; (offset = value.indexOf(needle, offset)) >= 0;
                offset += needle.length()) {
            count++;
        }
        return count;
    }

    @Test
    void parseModeUsesForwardReferenceFactsAndOneLogicalLifecycle()
            throws Exception {
        String pattern = "(?<b>\\g{c})(?<c>x)(?&b)";
        String trace = execute("use re Debug => 'PARSE'; qr{" + pattern + "}");

        assertTrue(trace.contains("Compiling REx \"" + pattern + "\""), trace);
        assertTrue(trace.contains("Assembling pattern from 1 elements"), trace);
        assertTrue(trace.contains("Starting parse and generation"), trace);
        assertTrue(trace.contains("Need to redo parse"), trace);
        assertFalse(trace.contains("JONI_PATTERN native bytecode"), trace);
    }

    @Test
    void parseModeDoesNotInventASecondPassForResolvedReferences()
            throws Exception {
        String trace = execute(
                "use re Debug => 'PARSE'; qr{(?<b>x)\\g{b}}");

        assertFalse(trace.contains("Need to redo parse"), trace);
        assertTrue(trace.contains("Starting parse and generation"), trace);
    }

    @Test
    void repeatedIdenticalCompilationsEachEmitTheirOwnTrace() throws Exception {
        String pattern = "regex_implementation_repeated_parse_trace";
        String trace = execute("use re Debug => 'PARSE'; qr{" + pattern
                + "}; qr{" + pattern + "}");

        assertEquals(2, occurrences(trace, "Compiling REx \"" + pattern + "\""),
                trace);
        assertEquals(2, occurrences(trace, "Starting parse and generation"), trace);
    }
}
