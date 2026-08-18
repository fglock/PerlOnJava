package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RegexBackendPolicyTest {
    private String originalBackendProperty;

    @BeforeEach
    void rememberBackendProperty() {
        originalBackendProperty = System.getProperty(RegexBackendPolicy.PROPERTY);
        System.clearProperty(RegexBackendPolicy.PROPERTY);
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
    void defaultModeUsesJavaForOrdinaryPatterns() {
        assertFalse(RegexBackendPolicy.useJoni("ordinary"));
        assertTrue(RegexBackendPolicy.useJoni("(?&recursive)"));
    }

    @Test
    void autoModeUsesJavaForOrdinaryPatterns() {
        System.setProperty(RegexBackendPolicy.PROPERTY, "auto");

        assertFalse(RegexBackendPolicy.useJoni("ordinary"));
        assertFalse(RegexBackendPolicy.useJoni("\\p{Titlecase}"));
        assertFalse(RegexBackendPolicy.useJoni("\\p{XPosixSpace}"));
    }

    @Test
    void autoModeRetainsRequiredAdvancedJoniRouting() {
        System.setProperty(RegexBackendPolicy.PROPERTY, "auto");

        assertTrue(RegexBackendPolicy.useJoni("(?{=CALL:0})"));
        assertTrue(RegexBackendPolicy.useJoni("(?{=DYNAMIC:0})"));
        assertTrue(RegexBackendPolicy.useJoni("(?<=x)"));
        assertTrue(RegexBackendPolicy.useJoni("(*:mark)"));
    }

    @Test
    void javaModeRetainsRequiredAdvancedJoniRouting() {
        System.setProperty(RegexBackendPolicy.PROPERTY, "java");

        assertFalse(RegexBackendPolicy.useJoni("ordinary"));
        assertTrue(RegexBackendPolicy.useJoni("(?&recursive)"));
    }

    @Test
    void joniModeRoutesOrdinaryPatternsToJoni() {
        System.setProperty(RegexBackendPolicy.PROPERTY, "joni");

        assertTrue(RegexBackendPolicy.useJoni("ordinary"));
    }

    @Test
    void invalidModeFailsInsteadOfSilentlyChangingSemantics() {
        System.setProperty(RegexBackendPolicy.PROPERTY, "unknown");

        assertThrows(IllegalArgumentException.class, RegexBackendPolicy::current);
    }
}
