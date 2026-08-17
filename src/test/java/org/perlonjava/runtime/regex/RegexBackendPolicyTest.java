package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RegexBackendPolicyTest {
    @AfterEach
    void clearBackendProperty() {
        System.clearProperty(RegexBackendPolicy.PROPERTY);
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
