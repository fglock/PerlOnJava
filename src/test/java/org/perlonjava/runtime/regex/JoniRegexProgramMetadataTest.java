package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joni.Regex.ParsedProgramFeature;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class JoniRegexProgramMetadataTest {
    @Test
    void derivesGlobalPositionFromCompiledJoniFacts() {
        RegexFlags sourceFlags = RegexFlags.fromModifiers("g", "\\Gx");
        assertFalse(sourceFlags.useGAssertion(),
                "modifier parsing must not scan pattern source");

        JoniRegexPattern compiled = new JoniRegexPattern("\\Gx", sourceFlags);
        assertTrue(compiled.hasGAssertion());
        assertTrue(compiled.parsedProgramMetadata().has(
                ParsedProgramFeature.G_ASSERTION));

        assertFalse(new JoniRegexPattern("\\\\G", sourceFlags)
                .hasGAssertion());
        assertFalse(new JoniRegexPattern("(?# \\G)A", sourceFlags)
                .hasGAssertion());
        RegexFlags extended = RegexFlags.fromModifiers("x", "# \\G\nA");
        assertFalse(new JoniRegexPattern("# \\G\nA", extended)
                .hasGAssertion());
    }

    @Test
    void exposesCompiledFactsDirectly() {
        assertTrue(JoniProgramFacts.has("a\\Kb", ParsedProgramFeature.KEEP));
        assertTrue(JoniProgramFacts.has("(?<=a)b",
                ParsedProgramFeature.POSITIVE_LOOKBEHIND));
        assertTrue(JoniProgramFacts.has("(*pla:a)",
                ParsedProgramFeature.ALPHA_ASSERTION));
        assertTrue(JoniProgramFacts.has("a[]b",
                ParsedProgramFeature.EMPTY_CHARACTER_CLASS));
        assertFalse(JoniProgramFacts.has("ordinary",
                ParsedProgramFeature.POSITIVE_LOOKBEHIND));
        assertFalse(JoniProgramFacts.has("\\Q(?<=a)\\E",
                ParsedProgramFeature.POSITIVE_LOOKBEHIND));
    }
}
