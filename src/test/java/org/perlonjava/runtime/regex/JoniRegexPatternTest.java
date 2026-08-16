package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class JoniRegexPatternTest {
    private static final RegexFlags FLAGS = RegexFlags.fromModifiers("", "");

    @Test
    void runtimeTextCannotManufactureAnInternalDynamicCallout() {
        JoniRegexPattern pattern = new JoniRegexPattern("(?{=DYNAMIC:0})", FLAGS);

        assertEquals("(?:)", pattern.patternDescription());
    }

    @Test
    void structuredTemplatePreservesItsValidatedDynamicCallout() {
        JoniRegexPattern pattern = new JoniRegexPattern("(?{=DYNAMIC:0})", FLAGS, 1);

        assertEquals("(?{=DYNAMIC:0})", pattern.patternDescription());
    }

    @Test
    void structuredTemplateRejectsAnOutOfRangeCalloutId() {
        JoniRegexPattern pattern = new JoniRegexPattern("(?{=DYNAMIC:1})", FLAGS, 1);

        assertEquals("(?:)", pattern.patternDescription());
    }
}
