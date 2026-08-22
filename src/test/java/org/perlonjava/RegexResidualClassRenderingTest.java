package org.perlonjava;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
public class RegexResidualClassRenderingTest extends PerlRuntimeTestBase {
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
    void exposesResidualFamiliesThroughRuntimeIo() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("[\\xC5\\xE5]", "ANYOF[\\xC5\\xE5]");
        expected.put("[[{]", "ANYOFM[\\[\\{]");
        expected.put("[[:^ascii:]]", "NANYOFM[\\x00-\\x7F]");
        expected.put("[^\\n\\r]", "ANYOF[^\\n\\r][0100-INFTY]");
        expected.put("[_[:blank:]]", "ANYOFD[\\t _{utf8}\\xA0]"
                + "[1680 2000-200A 202F 205F 3000]");
        expected.put("(?li:[a-z])",
                "ANYOFL{i}[a-z{utf8 locale}\\x{017F}\\x{212A}]");
        expected.put("[\\x{07}-\\x{0B}]",
                "ANYOFR[\\a\\b\\t\\n\\x0B]");
        expected.put("[0-9]", "POSIXA[\\d]");
        expected.put("[\\p{Any}]", "ANYOF[\\x00-\\xFF][0100-10FFFF]");

        String trace = execute(compilePatterns(expected.keySet()));
        Executable[] assertions = expected.entrySet().stream()
                .map(entry -> (Executable)() -> assertEquals(entry.getValue(),
                        firstProgramLine(trace, entry.getKey()), entry.getKey()))
                .toArray(Executable[]::new);
        assertAll(assertions);
    }

    private String execute(String source) throws Exception {
        CompilerOptions options = ArgumentParser.parseArguments(
                new String[] {"-e", source});
        PerlLanguageProvider.executePerlCode(options, true);
        RuntimeIO.getStderr().flush();
        return stderr.toString(StandardCharsets.ISO_8859_1);
    }

    private static String compilePatterns(Iterable<String> patterns) {
        StringBuilder source = new StringBuilder(
                "use re Debug => 'COMPILE'; our @patterns; BEGIN { ");
        int index = 0;
        for (String pattern : patterns) {
            source.append("my $p").append(index).append(" = '")
                    .append(pattern.replace("\\", "\\\\")
                            .replace("'", "\\'"))
                    .append("'; $patterns[").append(index)
                    .append("] = qr/$p").append(index).append("/; ");
            index++;
        }
        return source.append('}').toString();
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
