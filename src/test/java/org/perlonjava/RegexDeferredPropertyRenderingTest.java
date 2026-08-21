package org.perlonjava;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.perlonjava.app.cli.ArgumentParser;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.io.StandardIO;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;

@Tag("unit")
public class RegexDeferredPropertyRenderingTest extends PerlRuntimeTestBase {
    private static final String LEGACY_PLACEHOLDER = "Final program:\n"
            + "JONI_PATTERN deferred user-property placeholder; "
            + "native bytecode pending runtime resolution\n";

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

    @Test
    void rendersEightImportedDeferredPrograms() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("[^[:^print:][:^ascii:]b\\p{Is_unresolved}]",
                "ANYOF[^\\x00-\\x1Fb\\x7F-\\xFF"
                        + "{+main::Is_unresolved}0100-INFTY]");
        expected.put("[\\p{IsMyRuntimeProperty}]",
                "ANYOF[+main::IsMyRuntimeProperty]");
        expected.put("[^\\p{IsMyRuntimeProperty}]",
                "ANYOF[^{+main::IsMyRuntimeProperty}]");
        expected.put("[a\\p{IsMyRuntimeProperty}]",
                "ANYOF[a][+main::IsMyRuntimeProperty]");
        expected.put("[^a\\p{IsMyRuntimeProperty}]",
                "ANYOF[^a{+main::IsMyRuntimeProperty}]");
        expected.put("[^a\\x{100}\\p{IsMyRuntimeProperty}]",
                "ANYOF[^a{+main::IsMyRuntimeProperty}0100]");
        expected.put("[^\\p{All}\\p{IsMyRuntimeProperty}]", "OPFAIL");
        expected.put("[\\p{All}\\p{IsMyRuntimeProperty}]", "SANY");

        String trace = execute(compilePatterns(expected.keySet()));
        Executable[] assertions = expected.entrySet().stream()
                .map(entry -> (Executable)() -> assertEquals(entry.getValue(),
                        firstProgramLine(trace, entry.getKey()), entry.getKey()))
                .toArray(Executable[]::new);
        assertAll(assertions);
    }

    @Test
    void descriptionRemainsLazyAndResolutionRunsOnceOnReach() throws Exception {
        String trace = execute("use re Debug => 'COMPILE'; "
                + "our ($r, $calls, $description_calls); "
                + "BEGIN { $r = qr/^\\p{IsA27Lazy}$/; "
                + "$description_calls = $calls || 0; } "
                + "die unless $description_calls == 0; $calls ||= 0; "
                + "eval q{sub IsA27Lazy { ++$calls; qq{0041\\n} }}; "
                + "die $@ if $@; die unless 'A' =~ $r; "
                + "die unless 'A' =~ $r; die unless $calls == 1;");

        assertTrue(trace.contains("Compiling REx \"^\\p{IsA27Lazy}$\""), trace);
        assertTrue(trace.contains(LEGACY_PLACEHOLDER), trace);
    }

    @Test
    void preservesSourceOrderAndConstructionPackage() throws Exception {
        String ordered = "[\\p{IsA27One}\\P{IsA27Two}]";
        String packaged = "[\\p{IsLocal}]";
        String trace = execute("use re Debug => 'COMPILE'; our ($one, $two); "
                + "BEGIN { my $ordered = '" + perlSingleQuoted(ordered) + "'; "
                + "$one = qr/$ordered/; { package A27DeferredPackage; "
                + "my $packaged = '" + perlSingleQuoted(packaged) + "'; "
                + "$main::two = qr/$packaged/; } }");

        assertAll(
                () -> assertEquals("ANYOF[+main::IsA27One !main::IsA27Two]",
                        firstProgramLine(trace, ordered)),
                () -> assertEquals("ANYOF[+A27DeferredPackage::IsLocal]",
                        firstProgramLine(trace, packaged)));
    }

    @Test
    void keepsInternalBytesPrivateAndLegacyPlaceholderVisible() throws Exception {
        String pattern = "[\\p{IsA27Private}]";
        String trace = execute(compilePatterns(java.util.List.of(pattern)));

        assertFalse(trace.contains("\u001e"), trace);
        assertFalse(trace.contains("\u001f"), trace);
        assertFalse(trace.contains("?B0?"), trace);
        assertTrue(trace.contains(LEGACY_PLACEHOLDER), trace);
    }

    private static String compilePatterns(Iterable<String> patterns) {
        StringBuilder source = new StringBuilder(
                "use re Debug => 'COMPILE'; our @patterns; BEGIN { ");
        int index = 0;
        for (String pattern : patterns) {
            source.append("my $p").append(index).append(" = '")
                    .append(perlSingleQuoted(pattern)).append("'; ")
                    .append("$patterns[").append(index).append("] = qr/$p")
                    .append(index).append("/; ");
            index++;
        }
        return source.append('}').toString();
    }

    private static String perlSingleQuoted(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String firstProgramLine(String trace, String pattern) {
        String compilation = "Compiling REx \"" + pattern + "\"";
        int compilationOffset = trace.indexOf(compilation);
        assertTrue(compilationOffset >= 0, trace);
        int finalProgramOffset = trace.indexOf("Final program:\n",
                compilationOffset);
        assertTrue(finalProgramOffset >= 0, trace);
        int lineStart = finalProgramOffset + "Final program:\n".length();
        int lineEnd = trace.indexOf('\n', lineStart);
        assertTrue(lineEnd >= 0, trace);
        return trace.substring(lineStart, lineEnd).trim();
    }
}
