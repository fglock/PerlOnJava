package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.joni.exception.SyntaxException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class JoniNestedQuantifierPatternTest {
    @Test
    void rejectsNestedIntervalModifiersBeforeRuntimeWrapping() {
        assertThrows(SyntaxException.class, () -> new JoniRegexPattern(
                ".{1}??", RegexFlags.fromModifiers("", ".{1}??")));
        assertThrows(SyntaxException.class, () -> new JoniRegexPattern(
                ".{1}?+", RegexFlags.fromModifiers("", ".{1}?+")));
    }
}
