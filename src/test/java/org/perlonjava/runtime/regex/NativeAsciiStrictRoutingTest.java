package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class NativeAsciiStrictRoutingTest {
    private String originalBackendProperty;

    @BeforeEach
    void forceHistoricalJavaPolicySpelling() {
        originalBackendProperty = System.getProperty(RegexBackendPolicy.PROPERTY);
        System.setProperty(RegexBackendPolicy.PROPERTY, "java");
    }

    @AfterEach
    void restoreBackendProperty() {
        if (originalBackendProperty == null) {
            System.clearProperty(RegexBackendPolicy.PROPERTY);
        } else {
            System.setProperty(RegexBackendPolicy.PROPERTY, originalBackendProperty);
        }
    }

    @Test
    void routesTopLevelAndInlineAsciiStrictPatternsToJoni() {
        String ordinary = "sharp-s";
        RegexFlags topLevel = RegexFlags.fromModifiers("iaa", ordinary);

        assertTrue(RegexBackendPolicy.useJoni(ordinary, topLevel));
        assertTrue(RegexBackendPolicy.useJoni("(?aa:sharp-s)"));
        assertTrue(RegexBackendPolicy.useJoni("(?iaa:sharp-s)"));
        assertTrue(RegexBackendPolicy.useJoni("(?^aa:sharp-s)"));
        assertTrue(RegexBackendPolicy.useJoni("outer(?i:(?aa:inner))"));
        assertTrue(RegexBackendPolicy.useJoni("(?aa)sharp-s"));
    }

    @Test
    void ignoresAsciiStrictLookalikes() {
        assertFalse(RegexBackendPolicy.useJoni("(?a:ordinary)"));
        assertFalse(RegexBackendPolicy.useJoni("\\(\\?aa:escaped\\)"));
        assertFalse(RegexBackendPolicy.useJoni("[(?aa:class)]"));
        assertFalse(RegexBackendPolicy.useJoni("\\Q(?aa:quoted)\\E"));
        assertFalse(RegexBackendPolicy.useJoni("(?# (?aa:commented))ordinary"));

        String extendedComment = "# (?aa:commented)\nordinary";
        RegexFlags extendedFlags = RegexFlags.fromModifiers("x", extendedComment);
        assertFalse(RegexBackendPolicy.useJoni(extendedComment, extendedFlags));
    }
}
