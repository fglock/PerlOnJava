package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.joni.Regex.ParsedProgramFeature.EMPTY_CHARACTER_CLASS;
import static org.perlonjava.runtime.regex.JoniProgramFacts.has;

@Tag("unit")
class NativeEmptyClassRoutingTest {
    @Test
    void routesPerlEmptyClassToJoni() {
        assertTrue(has("a[]b", EMPTY_CHARACTER_CLASS));
        assertTrue(has("(?i:a[]b)", EMPTY_CHARACTER_CLASS));
    }

    @Test
    void ignoresEscapedAndQuotedBracketPairs() {
        assertFalse(has("a\\[]b", EMPTY_CHARACTER_CLASS));
        assertFalse(has("\\Q[]\\E", EMPTY_CHARACTER_CLASS));
    }
}
