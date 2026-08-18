package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;

class NativeKeepRoutingTest {
    @Test
    void admitsOnlyRealKeepAssertions() {
        assertTrue(JoniRegexPattern.requiresJoniBackend("ab\\Kcd"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("ab\\\\Kcd"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("[\\K]"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("[[:alpha:]\\K]"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("\\Q\\K\\E"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("(?# \\K ignored)abc"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("(?[ [\\K] ])"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("abc\\"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("\\k<name>"));
    }

    @Test
    void ignoresKeepTextInExtendedComments() {
        String pattern = "abc # \\K ignored\ndef";
        RegexFlags flags = RegexFlags.fromModifiers("x", pattern);
        assertFalse(JoniRegexPattern.requiresJoniBackend(pattern, flags));
    }

    @Test
    void rejectsKeepInsideLookaroundBeforeJoniCompilation() {
        for (String pattern : new String[] {
                "ab(?=c\\Kd)", "ab(?!c\\Kd)", "(?<=a\\Kb)c", "(?<!a\\Kb)c"
        }) {
            RegexFlags flags = RegexFlags.fromModifiers("", pattern);
            PerlCompilerException error = assertThrows(PerlCompilerException.class,
                    () -> new JoniRegexPattern(pattern, flags));
            assertTrue(error.getMessage().contains(
                    "\\K not permitted in lookahead/lookbehind"));
        }
    }
}
