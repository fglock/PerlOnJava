package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

@Tag("unit")
class NativeLookbehindRoutingTest {
    @Test
    void routesOnlySyntacticallyRealLookbehindsToJoni() {
        assertTrue(JoniRegexPattern.requiresJoniBackend("(?<=ab)c"));
        assertTrue(JoniRegexPattern.requiresJoniBackend("(?<!ab)c"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("\\(?<=ab\\)c"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("[(?<=ab)]c"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("\\Q(?<=ab)\\Ec"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("(?# (?<=ab))c"));
    }

    @Test
    void ignoresLookbehindTextInExtendedComments() {
        String pattern = "abc # (?<=ignored)\ndef";
        RegexFlags flags = RegexFlags.fromModifiers("x", pattern);
        assertFalse(JoniRegexPattern.requiresJoniBackend(pattern, flags));
    }
}
