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
public class RegexDeferredDebugTraceTest extends PerlRuntimeTestBase {
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
    void describesDeferredProgramWithoutPublishingPlaceholderBytecode() throws Exception {
        String trace = execute("use re Debug => 'COMPILE'; "
                + "our $r; BEGIN { my $p = "
                + "'[^[:^print:][:^ascii:]b\\p{IsRegexImplementationDeferredDebug}]'; "
                + "$r = qr/$p/; }");

        assertTrue(trace.contains(
                "Compiling REx \"[^[:^print:][:^ascii:]b"
                + "\\p{IsRegexImplementationDeferredDebug}]\""), trace);
        assertTrue(trace.contains("Final program:\n"
                + "JONI_PATTERN deferred user-property placeholder; "
                + "native bytecode pending runtime resolution\n"), trace);
        assertFalse(trace.contains("code length:"), trace);
        assertFalse(trace.contains("minlen "), trace);
    }

    @Test
    void reportsOneDeferredProgramPerRuntimeCacheKey() throws Exception {
        String trace = execute("use re Debug => 'COMPILE'; "
                + "our ($left, $right); BEGIN { "
                + "my $p = '\\p{IsRegexImplementationDeferredCache}'; "
                + "$left = qr/$p/; $right = qr/$p/; }");

        assertEquals(1, occurrences(trace,
                "Compiling REx \"\\p{IsRegexImplementationDeferredCache}\""), trace);
        assertEquals(1, occurrences(trace,
                "native bytecode pending runtime resolution"), trace);
    }

    @Test
    void runtimeResolutionReplacesDeferredReportWithNativeProgram() throws Exception {
        String trace = execute("use re Debug => 'COMPILE'; our $r; "
                + "our $calls = 0; "
                + "BEGIN { $r = qr/\\p{IsRegexImplementationForwardDebug}/; } "
                + "eval q{sub IsRegexImplementationForwardDebug { ++$calls; qq{0041\\n} }}; "
                + "die $@ if $@; die unless 'A' =~ $r; die unless 'A' =~ $r; "
                + "die unless $calls == 1;");

        assertEquals(2, occurrences(trace,
                "Compiling REx \"\\p{IsRegexImplementationForwardDebug}\""), trace);
        assertEquals(1, occurrences(trace,
                "native bytecode pending runtime resolution"), trace);
        assertEquals(1, occurrences(trace, "JONI_PATTERN native bytecode:"), trace);
        assertEquals(1, occurrences(trace, "code length:"), trace);
        assertEquals(1, occurrences(trace,
                "Freeing REx: \"\\p{IsRegexImplementationForwardDebug}\""), trace);
    }

    @Test
    void trackedDeferredClonesReuseOneResolvedNativeProgram() throws Exception {
        String trace = execute("use re Debug => 'COMPILE'; our $r; "
                + "BEGIN { $r = qr/\\p{IsRegexImplementationCloneDebug}/; } "
                + "my $left = qr/$r/; my $right = qr/$r/; "
                + "eval q{sub IsRegexImplementationCloneDebug { qq{0041\\n} }}; "
                + "die $@ if $@; die unless 'A' =~ $left; "
                + "die unless 'A' =~ $right;");

        assertEquals(1, occurrences(trace,
                "native bytecode pending runtime resolution"), trace);
        assertEquals(1, occurrences(trace, "JONI_PATTERN native bytecode:"), trace);
    }

    @Test
    void executeOnlyDeferredProgramKeepsOneLogicalLifecycle() throws Exception {
        String trace = execute("use re Debug => 'EXECUTE'; our $r; "
                + "BEGIN { $r = qr/\\p{IsRegexImplementationExecuteDebug}/; } "
                + "eval q{sub IsRegexImplementationExecuteDebug { qq{0041\\n} }}; "
                + "die $@ if $@; die unless 'A' =~ $r;");

        assertFalse(trace.contains("Compiling REx"), trace);
        assertFalse(trace.contains("native bytecode pending runtime resolution"), trace);
        assertTrue(trace.contains(
                "Matching REx \"\\p{IsRegexImplementationExecuteDebug}\""), trace);
        assertEquals(1, occurrences(trace,
                "Freeing REx: \"\\p{IsRegexImplementationExecuteDebug}\""), trace);
    }

    @Test
    void deferredCalloutKeepsOriginalSourceAcrossResolvedLifecycle() throws Exception {
        String trace = execute("use re 'eval'; use re Debug => 'ALL'; "
                + "our ($r, $calls); BEGIN { $r = qr/"
                + "(?{ ++$main::calls })\\p{IsRegexImplementationCalloutDebug}/; } "
                + "eval q{sub IsRegexImplementationCalloutDebug { qq{0041\\n} }}; "
                + "die $@ if $@; die unless 'A' =~ $r; die unless $calls == 1;");
        String source = "(?{ ++$main::calls })\\p{IsRegexImplementationCalloutDebug}";

        assertEquals(2, occurrences(trace, "Compiling REx \"" + source + "\""), trace);
        assertTrue(trace.contains("Matching REx \"" + source + "\""), trace);
        assertEquals(1, occurrences(trace, "Freeing REx: \"" + source + "\""), trace);
        assertFalse(trace.contains("\u001e"), trace);
        assertFalse(trace.contains("\u001f"), trace);
        assertFalse(trace.contains("?B0?"), trace);
    }

    @Test
    void sameNativeSkeletonKeepsEachConstructionSource() throws Exception {
        String trace = execute("use re 'eval'; use re Debug => 'EXECUTE'; "
                + "our ($one, $two); "
                + "my $first = '(?{ ++$main::one })A'; "
                + "my $second = '(?{ ++$main::two })A'; "
                + "die unless 'A' =~ /$first/; die unless 'A' =~ /$second/; "
                + "die unless $one == 1 && $two == 1;");

        assertTrue(trace.contains(
                "Matching REx \"(?{ ++$main::one })A\""), trace);
        assertTrue(trace.contains(
                "Matching REx \"(?{ ++$main::two })A\""), trace);
        assertFalse(trace.contains("\u001e"), trace);
        assertFalse(trace.contains("\u001f"), trace);
    }
}
