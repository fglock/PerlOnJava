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
                + "'[^[:^print:][:^ascii:]b\\p{IsPhase36DeferredDebug}]'; "
                + "$r = qr/$p/; }");

        assertTrue(trace.contains(
                "Compiling REx \"[^[:^print:][:^ascii:]b"
                + "\\p{IsPhase36DeferredDebug}]\""), trace);
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
                + "my $p = '\\p{IsPhase36DeferredCache}'; "
                + "$left = qr/$p/; $right = qr/$p/; }");

        assertEquals(1, occurrences(trace,
                "Compiling REx \"\\p{IsPhase36DeferredCache}\""), trace);
        assertEquals(1, occurrences(trace,
                "native bytecode pending runtime resolution"), trace);
    }

    @Test
    void runtimeResolutionReplacesDeferredReportWithNativeProgram() throws Exception {
        String trace = execute("use re Debug => 'COMPILE'; our $r; "
                + "our $calls = 0; "
                + "BEGIN { $r = qr/\\p{IsPhase36ForwardDebug}/; } "
                + "eval q{sub IsPhase36ForwardDebug { ++$calls; qq{0041\\n} }}; "
                + "die $@ if $@; die unless 'A' =~ $r; die unless 'A' =~ $r; "
                + "die unless $calls == 1;");

        assertEquals(2, occurrences(trace,
                "Compiling REx \"\\p{IsPhase36ForwardDebug}\""), trace);
        assertEquals(1, occurrences(trace,
                "native bytecode pending runtime resolution"), trace);
        assertEquals(1, occurrences(trace, "JONI_PATTERN native bytecode:"), trace);
        assertEquals(1, occurrences(trace, "code length:"), trace);
        assertEquals(1, occurrences(trace,
                "Freeing REx: \"\\p{IsPhase36ForwardDebug}\""), trace);
    }

    @Test
    void trackedDeferredClonesReuseOneResolvedNativeProgram() throws Exception {
        String trace = execute("use re Debug => 'COMPILE'; our $r; "
                + "BEGIN { $r = qr/\\p{IsPhase36CloneDebug}/; } "
                + "my $left = qr/$r/; my $right = qr/$r/; "
                + "eval q{sub IsPhase36CloneDebug { qq{0041\\n} }}; "
                + "die $@ if $@; die unless 'A' =~ $left; "
                + "die unless 'A' =~ $right;");

        assertEquals(1, occurrences(trace,
                "native bytecode pending runtime resolution"), trace);
        assertEquals(1, occurrences(trace, "JONI_PATTERN native bytecode:"), trace);
    }

    @Test
    void executeOnlyDeferredProgramKeepsOneLogicalLifecycle() throws Exception {
        String trace = execute("use re Debug => 'EXECUTE'; our $r; "
                + "BEGIN { $r = qr/\\p{IsPhase36ExecuteDebug}/; } "
                + "eval q{sub IsPhase36ExecuteDebug { qq{0041\\n} }}; "
                + "die $@ if $@; die unless 'A' =~ $r;");

        assertFalse(trace.contains("Compiling REx"), trace);
        assertFalse(trace.contains("native bytecode pending runtime resolution"), trace);
        assertTrue(trace.contains(
                "Matching REx \"\\p{IsPhase36ExecuteDebug}\""), trace);
        assertEquals(1, occurrences(trace,
                "Freeing REx: \"\\p{IsPhase36ExecuteDebug}\""), trace);
    }

    @Test
    void deferredCalloutKeepsOriginalSourceAcrossResolvedLifecycle() throws Exception {
        String trace = execute("use re 'eval'; use re Debug => 'ALL'; "
                + "our ($r, $calls); BEGIN { $r = qr/"
                + "(?{ ++$main::calls })\\p{IsPhase36CalloutDebug}/; } "
                + "eval q{sub IsPhase36CalloutDebug { qq{0041\\n} }}; "
                + "die $@ if $@; die unless 'A' =~ $r; die unless $calls == 1;");
        String source = "(?{ ++$main::calls })\\p{IsPhase36CalloutDebug}";

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
