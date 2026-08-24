package org.perlonjava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** Captured host contract for the opt-in native parser trace. */
@Tag("unit")
class RegexNativeParseDebugTranscriptTest extends PerlRuntimeTestBase {
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

    @Test
    void rendersResolvedForwardReferenceProgramOncePerLiteral()
            throws Exception {
        String pattern = "(?<b>\\g{c})(?<c>x)(?&b)";
        String trace = execute("use re Debug => 'PARSE'; qr{" + pattern + "}");

        assertEquals(1, occurrences(trace, "Compiling REx \"" + pattern + "\""),
                trace);
        assertEquals(1, occurrences(trace, "Need to redo parse"), trace);
        assertEquals(2, occurrences(trace, "Freeing REx: \"" + pattern + "\""),
                trace);
        assertTrue(trace.contains(
                "<(?<b>\\g{c})>...|   1|  reg    \n"), trace);
        assertTrue(trace.contains(
                "|   8|          lsbr~ tying lastbr REFN2 'c' <1> (3) "
                        + "to ender CLOSE1 'b' (6) offset 3\n"), trace);
        assertTrue(trace.contains(
                "|    |        ~ GOSUB1[+0:14] 'b' (14) -> END\n"), trace);
        assertFalse(trace.contains("@byte"), trace);
        assertOrdered(trace, "tail~ REFN <1>", "Need to redo parse", "REFN2 'c'",
                "GOSUB1[+0:14] 'b'", "Required size 17 nodes", "first at 3");
    }

    @Test
    void identicalSourceSitesKeepDistinctConstructionLifecycles()
            throws Exception {
        String pattern = "regex_implementation_native_parse_identity";
        String trace = execute("use re Debug => 'PARSE'; qr{" + pattern
                + "}; qr{" + pattern + "}");

        assertEquals(2, occurrences(trace, "Compiling REx \"" + pattern + "\""),
                trace);
        assertEquals(2, occurrences(trace, "Freeing REx: \"" + pattern + "\""),
                trace);
    }

    @Test
    void failedCompilationRendersTheAcceptedLogicalPrefix() throws Exception {
        FailedCompile failed = compileFailure(
                "use re Debug => 'PARSE'; qr{(?<b>\\g{c}}");

        assertTrue(failed.diagnostic.startsWith("Unmatched ("), failed.diagnostic);
        assertOrdered(failed.trace, "Setting open paren #1 to 1", "REFN 'c'",
                "CLOSE1 'b'", "END", "Freeing REx:");
    }

    @Test
    void namedAllRendersTheMaskedCalloutFailurePrefix() throws Exception {
        FailedCompile failed = compileFailure(
                "use re Debug => 'ALL'; qr{(?{a})(?<b>\\g{c}}");

        assertTrue(failed.diagnostic.startsWith("Unmatched ("), failed.diagnostic);
        assertTrue(failed.trace.contains("Assembling pattern from 2 elements\n"),
                failed.trace);
        assertTrue(failed.trace.contains(
                "<(?{a})(?<b>>...|   1|  reg    \n"), failed.trace);
        assertTrue(failed.trace.contains(
                "<>              |   9|            tail~ OPEN1 'b' (4) -> REFN\n"),
                failed.trace);
        assertTrue(failed.trace.contains(
                "|  11|          lsbr~ tying lastbr REFN <1> (6) "
                        + "to ender CLOSE1 'b' (9) offset 3\n"), failed.trace);
        assertFalse(failed.trace.contains("@byte"), failed.trace);
    }

    private String execute(String source) throws Exception {
        CompilerOptions options = ArgumentParser.parseArguments(
                new String[] {"-e", source});
        PerlLanguageProvider.executePerlCode(options, true);
        RuntimeIO.getStderr().flush();
        return stderr.toString(StandardCharsets.ISO_8859_1);
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

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int offset = 0; (offset = value.indexOf(needle, offset)) >= 0;
                offset += needle.length()) count++;
        return count;
    }

    private static void assertOrdered(String trace, String... needles) {
        int offset = 0;
        for (String needle : needles) {
            int found = trace.indexOf(needle, offset);
            assertTrue(found >= offset, trace);
            offset = found + needle.length();
        }
    }

    private record FailedCompile(String trace, String diagnostic) {}
}
