package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import static org.joni.Regex.ParsedProgramFeature.NEGATIVE_LOOKBEHIND;
import static org.joni.Regex.ParsedProgramFeature.POSITIVE_LOOKBEHIND;
import static org.perlonjava.runtime.regex.JoniProgramFacts.hasAny;

@Tag("unit")
class NativeLookbehindRoutingTest {
    @Test
    void routesOnlySyntacticallyRealLookbehindsToJoni() {
        assertTrue(hasAny("(?<=ab)c", POSITIVE_LOOKBEHIND, NEGATIVE_LOOKBEHIND));
        assertTrue(hasAny("(?<!ab)c", POSITIVE_LOOKBEHIND, NEGATIVE_LOOKBEHIND));
        assertFalse(hasAny("\\(?<=ab\\)c", POSITIVE_LOOKBEHIND, NEGATIVE_LOOKBEHIND));
        assertFalse(hasAny("[(?<=ab)]c", POSITIVE_LOOKBEHIND, NEGATIVE_LOOKBEHIND));
        assertFalse(hasAny("\\Q(?<=ab)\\Ec", POSITIVE_LOOKBEHIND, NEGATIVE_LOOKBEHIND));
        assertFalse(hasAny("(?# (?<=ab))c", POSITIVE_LOOKBEHIND, NEGATIVE_LOOKBEHIND));
    }

    @Test
    void ignoresLookbehindTextInExtendedComments() {
        String pattern = "abc # (?<=ignored)\ndef";
        RegexFlags flags = RegexFlags.fromModifiers("x", pattern);
        assertFalse(JoniProgramFacts.has(pattern, flags, POSITIVE_LOOKBEHIND));
    }
}
