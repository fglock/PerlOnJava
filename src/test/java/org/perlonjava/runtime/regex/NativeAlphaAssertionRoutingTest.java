package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class NativeAlphaAssertionRoutingTest {
    @Test
    void routesShortAndLongAlphaAssertionsToJoni() {
        assertTrue(JoniRegexPattern.requiresJoniBackend("a(*pla:b)b"));
        assertTrue(JoniRegexPattern.requiresJoniBackend("a(*positive_lookahead:b)b"));
        assertTrue(JoniRegexPattern.requiresJoniBackend("a(*plb:a)b"));
        assertTrue(JoniRegexPattern.requiresJoniBackend("a(*positive_lookbehind:a)b"));
        assertTrue(JoniRegexPattern.requiresJoniBackend("a(*nla:c)b"));
        assertTrue(JoniRegexPattern.requiresJoniBackend("a(*negative_lookahead:c)b"));
        assertTrue(JoniRegexPattern.requiresJoniBackend("a(*nlb:c)b"));
        assertTrue(JoniRegexPattern.requiresJoniBackend("a(*negative_lookbehind:c)b"));
        assertTrue(JoniRegexPattern.requiresJoniBackend("(*atomic:a|ab)c"));
        assertTrue(JoniRegexPattern.requiresJoniBackend("(*pla)"));
        assertTrue(JoniRegexPattern.requiresJoniBackend("(*positive_lookahead"));
    }

    @Test
    void ignoresAlphaAssertionLookalikes() {
        assertFalse(JoniRegexPattern.requiresJoniBackend("\\(\\*pla:a\\)"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("[(?*pla:)]"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("\\Q(*pla:a)\\E"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("(?# (*pla:a))ordinary"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("(*planet:a)"));
    }
}
