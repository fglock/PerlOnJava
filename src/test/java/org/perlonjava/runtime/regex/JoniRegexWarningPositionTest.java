package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class JoniRegexWarningPositionTest {
    private static final RegexFlags FLAGS = RegexFlags.fromModifiers("", "");

    @Test
    void formatsPerlNumericEscapeWarningAtEndOfPattern() {
        assertEquals(List.of(
                "Non-octal character '8' terminates \\o early.  "
                        + "Resolved as \"\\o{007}\" in regex; marked by <-- HERE in "
                        + "m/\\o{789} <-- HERE /"),
                new JoniRegexPattern("\\o{789}", FLAGS).compileWarnings());
    }

    @Test
    void convertsJoniBytePositionAfterUnicodePrefix() {
        assertEquals(List.of(
                "Non-octal character '8' terminates \\o early.  "
                        + "Resolved as \"\\o{007}\" in regex; marked by <-- HERE in "
                        + "m/ネ\\o{789} <-- HERE /"),
                new JoniRegexPattern("ネ\\o{789}", FLAGS).compileWarnings());
    }
}
