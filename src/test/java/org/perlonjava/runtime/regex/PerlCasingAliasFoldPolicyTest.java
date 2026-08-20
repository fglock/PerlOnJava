package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joni.CharacterPropertyResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlCasingAliasFoldPolicyTest {

    @Test
    void unicodePropertyAliasesShareTheirCanonicalCasingFoldPolicy() {
        for (String property : new String[] {"Upper", "Uppercase", "Lower", "Lowercase"}) {
            CharacterPropertyResolver.Result result =
                    UnicodeResolver.resolveJoniProperty(property, false, true);
            assertNotNull(result, property);
            assertTrue(result.caseFold, property);
        }
    }

    @Test
    void aliasRangesRemainDirectionalBeforeJoniAppliesIgnoreCase() {
        CharacterPropertyResolver.Result upper =
                UnicodeResolver.resolveJoniProperty("Upper", false, false);
        CharacterPropertyResolver.Result lower =
                UnicodeResolver.resolveJoniProperty("Lower", false, false);
        assertTrue(contains(upper.ranges, 'A'));
        assertFalse(contains(upper.ranges, 'a'));
        assertTrue(contains(lower.ranges, 'a'));
        assertFalse(contains(lower.ranges, 'A'));
    }

    private static boolean contains(int[] ranges, int codePoint) {
        for (int index = 0; index < ranges[0]; index++) {
            if (codePoint >= ranges[index * 2 + 1]
                    && codePoint <= ranges[index * 2 + 2]) return true;
        }
        return false;
    }
}
