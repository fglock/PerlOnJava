package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.joni.Regex.ParsedProgramFeature.BRANCH_RESET;
import static org.perlonjava.runtime.regex.JoniProgramFacts.has;

@Tag("unit")
class NativeBranchResetRoutingTest {
    @Test
    void routesOnlyRealBranchResetGroupsToJoni() {
        assertTrue(has("(?|(a)|(b))", BRANCH_RESET));
        assertFalse(has("\\(?|a\\)", BRANCH_RESET));
        assertFalse(has("[(?|a)]", BRANCH_RESET));
        assertFalse(has("\\Q(?|a)\\E", BRANCH_RESET));
        assertFalse(has("(?# (?|a))x", BRANCH_RESET));
    }
}
