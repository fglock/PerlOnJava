package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joni.CharacterPropertyResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeResidualPropertyDataTest {
    @Test
    void resolvesGeneratedEnumeratedPropertiesThroughJoni() {
        assertContains("InCB=Linker", 0x094D);
        assertContains("GCB=Extend", 0x0300);
        assertContains("Identifier_Type=Recommended", 'A');
        assertContains("kEH_Core=C", 0x13000);
        assertContains("kEH_Core=L", 0x1305D);
        assertContains("kEH_Core=N", 'A');
    }

    @Test
    void resolvesWildcardsAndRetainedEmptyValues() {
        assertContains("InCB=:^Consonant$:", 0x0915);
        assertContains("Identifier_Type=:^Recommended$:", 'A');
        CharacterPropertyResolver.Result empty =
                UnicodeResolver.resolveJoniProperty("GCB=:^E_Base$:", false);
        assertNotNull(empty);
        assertFalse(contains(empty.ranges, 0x1F466));
    }

    @Test
    void pinsHexDigitAheadOfTheOlderIcuData() {
        assertContains("Hex", 0xFF46);
        assertContains("Hex=True", 0xFF46);
        CharacterPropertyResolver.Result negative =
                UnicodeResolver.resolveJoniProperty("Hex=False", false);
        assertNotNull(negative);
        assertFalse(contains(negative.ranges, 0xFF46));
        assertTrue(contains(negative.ranges, 'G'));
    }

    private static void assertContains(String property, int codePoint) {
        CharacterPropertyResolver.Result result =
                UnicodeResolver.resolveJoniProperty(property, false, true);
        assertNotNull(result, property);
        assertTrue(contains(result.ranges, codePoint), property);
    }

    private static boolean contains(int[] ranges, int codePoint) {
        if (ranges == null) return false;
        for (int index = 1; index + 1 < ranges.length; index += 2) {
            if (ranges[index] <= codePoint && codePoint <= ranges[index + 1]) return true;
        }
        return false;
    }
}
