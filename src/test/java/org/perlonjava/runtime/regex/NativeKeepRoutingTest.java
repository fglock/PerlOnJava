package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import static org.joni.Regex.ParsedProgramFeature.KEEP;
import static org.perlonjava.runtime.regex.JoniProgramFacts.has;

class NativeKeepRoutingTest {
    @Test
    void admitsOnlyRealKeepAssertions() {
        assertTrue(has("ab\\Kcd", KEEP));
        assertFalse(has("ab\\\\Kcd", KEEP));
        assertFalse(has("[\\K]", KEEP));
        assertFalse(has("[[:alpha:]\\K]", KEEP));
        assertFalse(has("\\Q\\K\\E", KEEP));
        assertFalse(has("(?# \\K ignored)abc", KEEP));
        assertFalse(has("(?[ [\\K] ])", KEEP));
        assertFalse(has("abc\\", KEEP));
        assertFalse(has("\\k<name>", KEEP));
    }

    @Test
    void ignoresKeepTextInExtendedComments() {
        String pattern = "abc # \\K ignored\ndef";
        RegexFlags flags = RegexFlags.fromModifiers("x", pattern);
        assertFalse(JoniProgramFacts.has(pattern, flags, KEEP));
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
