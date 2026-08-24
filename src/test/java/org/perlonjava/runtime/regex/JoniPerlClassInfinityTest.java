package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.joni.exception.JOniException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.operators.PerlUtfString;

@Tag("unit")
class JoniPerlClassInfinityTest {
    private static final RegexFlags FLAGS = RegexFlags.fromModifiers("", "");

    @Test
    void productAdapterAcceptsInfinityOnlyAsARangeRightHandSide() {
        assertTrue(matches("[\\x{0}-\\x{FFFFFFFFFFFFFFFF}]", "A"));
        assertTrue(matches("[\\o{0}-\\o{1777777777777777777777}]", "A"));

        assertRejected("\\x{FFFFFFFFFFFFFFFF}");
        assertRejected("[\\x{FFFFFFFFFFFFFFFF}]");
        assertRejected("[\\x{FFFFFFFFFFFFFFFF}-\\x{FFFFFFFFFFFFFFFF}]");
        assertRejected("[\\x{0}-\\x{8000000000000000}]");
        assertRejected("[\\x{0}-\\x{FFFFFFFFFFFFFFFE}]");
    }

    @Test
    void highestAndInfinityRangesMatchEveryExecutableSampleIdentically() {
        String highest = "[\\x{101}-\\x{7FFFFFFFFFFFFFFF}]";
        String infinity = "[\\x{101}-\\x{FFFFFFFFFFFFFFFF}]";
        for (String subject : List.of("\u0101",
                PerlUtfString.encodeBeyondUnicode(0x110000),
                PerlUtfString.encodeBeyondUnicode(Long.MAX_VALUE))) {
            assertTrue(matches(highest, subject));
            assertTrue(matches(infinity, subject));
        }
        assertFalse(matches(highest, "\u0100"));
        assertFalse(matches(infinity, "\u0100"));
    }

    @Test
    void mergedAndFullInfinityClassesRetainProductMatchingBehavior() {
        String merged = "[\\x{10C}-\\x{FFFFFFFFFFFFFFFF}"
                + "\\x{102}-\\x{104}\\x{108}-\\x{10A}"
                + "\\x{103}-\\x{109}]";
        assertTrue(matches(merged, "\u0102"));
        assertTrue(matches(merged, "\u0109"));
        assertTrue(matches(merged, PerlUtfString.encodeBeyondUnicode(0x110000)));
        assertFalse(matches(merged, "\u010B"));

        for (String full : List.of(
                "[\\x{00}-\\x{FFFFFFFFFFFFFFFF}]",
                "[\\x{10C}-\\x{FFFFFFFFFFFFFFFF}"
                        + "\\x{00}-\\x{7FFFFFFFFFFFFFFF}]",
                "[\\x{10C}-\\x{FFFFFFFFFFFFFFFF}"
                        + "\\x{00}-\\x{FFFFFFFFFFFFFFFF}]")) {
            assertTrue(matches(full, "A"));
            assertTrue(matches(full,
                    PerlUtfString.encodeBeyondUnicode(Long.MAX_VALUE)));
        }
    }

    private static boolean matches(String source, String input) {
        RegexMatcher matcher = new JoniRegexPattern(
                "\\A(?:" + source + ")\\z", FLAGS)
                .matcher(input, List.of());
        return matcher.find();
    }

    private static void assertRejected(String source) {
        JOniException error = assertThrows(JOniException.class,
                () -> new JoniRegexPattern(source, FLAGS));
        assertTrue(error.getMessage().contains(
                "permissible max is 0x7FFFFFFFFFFFFFFF"));
    }
}
