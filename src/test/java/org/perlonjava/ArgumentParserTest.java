package org.perlonjava;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.ArgumentParser;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
public class ArgumentParserTest {

    @BeforeEach
    void setUp() {
        PerlLanguageProvider.resetAll();
    }

    @Test
    void rudimentarySwitchParsingContinuesAfterDoubleDashForEvalCode() {
        CompilerOptions options = ArgumentParser.parseArguments(new String[] {
                "-sweprint",
                "--",
                "-_=Just another Perl Hacker"
        });

        assertTrue(options.rudimentarySwitchParsing);
        assertEquals(0, options.argumentList.elements.size());
        assertEquals("$main::_ = 'Just another Perl Hacker';\nprint", options.code);
    }

    @Test
    void rudimentarySwitchParsingPreservesUtf8BytesWithoutUnicodeArgs() {
        CompilerOptions options = ArgumentParser.parseArguments(new String[] {
                "-C0", "-se1", "--", "-\u00c5\u00b8", "-\u00c3\u00a1=\u00e2\u0082\u00ac"
        });

        assertEquals(
                "${qq(\\x{6d}\\x{61}\\x{69}\\x{6e}\\x{3a}\\x{3a}\\x{c5}\\x{b8})} = '1';\n"
                        + "${qq(\\x{6d}\\x{61}\\x{69}\\x{6e}\\x{3a}\\x{3a}\\x{c3}\\x{a1})} = "
                        + "qq(\\x{e2}\\x{82}\\x{ac});\n1",
                options.code);
    }

    @Test
    void rudimentarySwitchParsingDecodesUtf8WithUnicodeArgs() {
        CompilerOptions options = ArgumentParser.parseArguments(new String[] {
                "-CA", "-se1", "--", "-\u00c5\u00b8", "-\u00c3\u00a1=\u00e2\u0082\u00ac"
        });

        assertEquals(
                "${qq(\\x{6d}\\x{61}\\x{69}\\x{6e}\\x{3a}\\x{3a}\\x{178})} = '1';\n"
                        + "${qq(\\x{6d}\\x{61}\\x{69}\\x{6e}\\x{3a}\\x{3a}\\x{e1})} = qq(\\x{20ac});\n1",
                options.code);
    }

    @Test
    void versionedPerlShebangRunsInCurrentRuntime() {
        CompilerOptions options = new CompilerOptions();

        assertTrue(ArgumentParser.applyPerlShebangSwitches(
                "#!/usr/bin/env perl5 -w\nprint qq(ok\\n);\n", options));
        assertTrue(options.perlShebangProcessed);
        assertTrue(options.warnFlag);
    }

    @Test
    void inlinePerlShebangAffectsTheImplicitLoop() {
        CompilerOptions options = ArgumentParser.parseArguments(new String[] {
                "-e", "#!perl -sn\nprint $x"
        });

        assertTrue(options.rudimentarySwitchParsing);
        assertTrue(options.processOnly);
        assertTrue(options.code.startsWith("while (<>) {"));
    }

    @Test
    void debuggerModuleSwitchLoadsTheRequestedDevelModule() {
        CompilerOptions options;
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            options = ArgumentParser.parseArguments(new String[] {
                    "-d:switchd=a,42", "-e", "print qq(ok\\n);"
            });
        }

        assertTrue(options.runUnderDebugger);
        assertTrue(options.useInterpreter);
        assertTrue(options.code.contains("use Devel::switchd (\"a\", \"42\");"));
    }

    @Test
    void moduleSwitchAcceptsAWhitespaceSeparatedModuleArgument() {
        CompilerOptions options = ArgumentParser.parseArguments(new String[] {
                "-Mless ++INC->{q-Devel/_.pm-}", "-e", "print qq(ok\\n);"
        });

        assertTrue(options.code.contains("use less ++INC->{q-Devel/_.pm-};"));
    }
}
