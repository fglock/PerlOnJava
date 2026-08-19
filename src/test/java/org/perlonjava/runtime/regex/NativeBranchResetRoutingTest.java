package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class NativeBranchResetRoutingTest {
    @Test
    void routesOnlyRealBranchResetGroupsToJoni() {
        assertTrue(JoniRegexPattern.requiresJoniBackend("(?|(a)|(b))"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("\\(?|a\\)"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("[(?|a)]"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("\\Q(?|a)\\E"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("(?# (?|a))x"));
    }
}
