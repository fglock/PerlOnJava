package org.perlonjava;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.ArgumentParser;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

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
}
