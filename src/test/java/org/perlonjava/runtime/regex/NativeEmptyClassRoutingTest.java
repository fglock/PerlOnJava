package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class NativeEmptyClassRoutingTest {
    @Test
    void routesPerlEmptyClassToJoni() {
        assertTrue(JoniRegexPattern.requiresJoniBackend("a[]b"));
        assertTrue(JoniRegexPattern.requiresJoniBackend("(?i:a[]b)"));
    }

    @Test
    void ignoresEscapedAndQuotedBracketPairs() {
        assertFalse(JoniRegexPattern.requiresJoniBackend("a\\[]b"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("\\Q[]\\E"));
    }
}
