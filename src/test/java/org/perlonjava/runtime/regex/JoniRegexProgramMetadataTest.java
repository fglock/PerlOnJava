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
    void compatibilityAdapterUsesCompiledFacts() {
        assertTrue(JoniRegexPattern.requiresJoniBackend("a\\Kb"));
        assertTrue(JoniRegexPattern.requiresJoniBackend("(?<=a)b"));
        assertTrue(JoniRegexPattern.requiresJoniBackend("(*pla:a)"));
        assertTrue(JoniRegexPattern.requiresJoniBackend("a[]b"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("ordinary"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("\\Q(?<=a)\\E"));
    }
}
