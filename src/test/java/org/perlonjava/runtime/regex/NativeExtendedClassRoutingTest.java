package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class NativeExtendedClassRoutingTest {
    private String originalBackendProperty;

    @BeforeEach
    void selectJavaDiagnosticMode() {
        originalBackendProperty = System.getProperty(RegexBackendPolicy.PROPERTY);
        System.setProperty(RegexBackendPolicy.PROPERTY, "java");
    }

    @AfterEach
    void restoreBackendProperty() {
        if (originalBackendProperty == null) System.clearProperty(RegexBackendPolicy.PROPERTY);
        else System.setProperty(RegexBackendPolicy.PROPERTY, originalBackendProperty);
    }

    @Test
    void routesRealExtendedClassesToNativeJoni() {
        assertTrue(RegexBackendPolicy.useJoni("(?[ [:ascii:] & [:graph:] ])"));
    }

    @Test
    void ignoresEscapedAndQuotedExtendedClassLookalikes() {
        assertFalse(RegexBackendPolicy.useJoni("\\(\\?\\["));
        assertFalse(RegexBackendPolicy.useJoni("\\Q(?[ [:ascii:] ])\\E"));
    }
}
