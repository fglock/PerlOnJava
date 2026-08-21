package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.PerlRuntimeTestBase;

@Tag("unit")
class JoniRegexWarningPositionTest extends PerlRuntimeTestBase {
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

    @Test
    void canonicalizesNamedEndpointsInMixedUnicodeRangeWarnings() {
        for (String[] testCase : new String[][] {
                {"[\\x00-\\N{SOH}]\\x{100}",
                 "[\\x00-\\N{U+01} <-- HERE ]\\x{100}"},
                {"[\\N{DEL}-\\o{377}]\\x{100}",
                 "[\\N{U+7F}-\\o{377} <-- HERE ]\\x{100}"},
                {"[\\N{DEL}-\\377]\\x{100}",
                 "[\\N{U+7F}-\\377 <-- HERE ]\\x{100}"},
        }) {
            JoniRegexPattern pattern = new JoniRegexPattern(
                    testCase[0], FLAGS, 0, false, false, false,
                    new JoniRegexPattern.NamedCharacterCache(), true);
            assertEquals(List.of(
                    "Both or neither range ends should be Unicode in regex; "
                            + "marked by <-- HERE in m/" + testCase[1] + "/"),
                    pattern.compileWarnings(), testCase[0]);
        }
    }
}
