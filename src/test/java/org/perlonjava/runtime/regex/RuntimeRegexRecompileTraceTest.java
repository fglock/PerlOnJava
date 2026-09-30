package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.io.StandardIO;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;

@Tag("unit")
class RuntimeRegexRecompileTraceTest {
    private static final RegexCase[] CASES = {
        new RegexCase("same dynamic source reuses the current pattern", 1, """
                use re qw(debug);
                "a" =~ /$_/ for qw(a a a);
                """),
        new RegexCase("dynamic source recompiles after a pattern transition", 3, """
                use re qw(debug);
                my ($a, $b) = ("\\x{c4}\\x{80}", "\\x{100}");
                "a" =~ /$_/ for $a, $b, $a;
                """),
        new RegexCase("runtime executable source reports both compile phases", 6, """
                use re qw(debug);
                my $code = '(?{1})';
                BEGIN { $^H |= 0x00200000 }
                "a" =~ /a$_/ for $code, $code, $code;
                """),
        new RegexCase("embedded callbacks compile for each qr evaluation", 6, """
                use re qw(debug);
                my $x = qr/a/i;
                my $y = qr/a/;
                "a" =~ qr/a$_/ for $x, $y, $x, $y;
                """),
        new RegexCase("dynamic qr callbacks retain their enclosing arguments", 2, """
                use re qw(debug);
                sub {
                    $_[0] = ${qr/abc/};
                    "bb" =~ /(??{$_[0]})/;
                }->($_[0]);
                """),
    };

    @Test
    void dynamicPatternCompileEventsMatchPerlOnBothBackends() throws Exception {
        for (boolean interpreter : new boolean[]{false, true}) {
            for (RegexCase regexCase : CASES) {
                assertEquals(regexCase.expectedFinalPrograms,
                        finalProgramCount(regexCase.source, interpreter),
                        regexCase.name + (interpreter ? " (interpreter)" : " (JVM)"));
            }
        }
    }

    private static int finalProgramCount(String source, boolean interpreter)
            throws Exception {
        PerlLanguageProvider.resetAll();
        PerlRuntime runtime = new PerlRuntime();
        ByteArrayOutputStream debugOutput = new ByteArrayOutputStream();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeIO.setStderr(new RuntimeIO(new StandardIO(debugOutput, false)));
            CompilerOptions options = new CompilerOptions();
            options.fileName = "<regex-recompile-trace-test>";
            options.code = source;
            options.useInterpreter = interpreter;
            PerlLanguageProvider.executePerlCode(options, true);
            RuntimeIO.getStderr().flush();
        } finally {
            RuntimeIO.setStderr(new RuntimeIO(new StandardIO(System.err, false)));
        }
        String output = debugOutput.toString(StandardCharsets.UTF_8);
        return (int) output.lines().filter("Final program:"::equals).count();
    }

    private record RegexCase(String name, int expectedFinalPrograms, String source) {}
}
