package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.joni.Regex.ParsedProgramFeature.ALPHA_ASSERTION;
import static org.perlonjava.runtime.regex.JoniProgramFacts.has;

@Tag("unit")
class NativeAlphaAssertionRoutingTest {
    @Test
    void routesShortAndLongAlphaAssertionsToJoni() {
        assertTrue(has("a(*pla:b)b", ALPHA_ASSERTION));
        assertTrue(has("a(*positive_lookahead:b)b", ALPHA_ASSERTION));
        assertTrue(has("a(*plb:a)b", ALPHA_ASSERTION));
        assertTrue(has("a(*positive_lookbehind:a)b", ALPHA_ASSERTION));
        assertTrue(has("a(*nla:c)b", ALPHA_ASSERTION));
        assertTrue(has("a(*negative_lookahead:c)b", ALPHA_ASSERTION));
        assertTrue(has("a(*nlb:c)b", ALPHA_ASSERTION));
        assertTrue(has("a(*negative_lookbehind:c)b", ALPHA_ASSERTION));
        assertTrue(has("(*atomic:a|ab)c", ALPHA_ASSERTION));
        assertTrue(has("(*pla)", ALPHA_ASSERTION));
        assertTrue(has("(*positive_lookahead", ALPHA_ASSERTION));
    }

    @Test
    void ignoresAlphaAssertionLookalikes() {
        assertFalse(has("\\(\\*pla:a\\)", ALPHA_ASSERTION));
        assertFalse(has("[(?*pla:)]", ALPHA_ASSERTION));
        assertFalse(has("\\Q(*pla:a)\\E", ALPHA_ASSERTION));
        assertFalse(has("(?# (*pla:a))ordinary", ALPHA_ASSERTION));
        assertFalse(has("(*planet:a)", ALPHA_ASSERTION));
    }
}
