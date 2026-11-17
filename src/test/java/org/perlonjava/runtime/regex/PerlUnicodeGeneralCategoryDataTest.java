package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeGeneralCategoryDataTest {
    @Test
    void usesPinnedPerl545Unicode18DataAndAliases() {
        assertEquals("18.0.0", PerlUnicodeGeneralCategoryData.UNICODE_VERSION);
        assertTrue(PerlUnicodeGeneralCategoryData.resolve("uppercase_letter").contains('A'));
        assertTrue(PerlUnicodeGeneralCategoryData.resolve(":\\ALu\\z:").contains('A'));
        assertTrue(PerlUnicodeGeneralCategoryData.resolve("L").contains('A'));
        assertTrue(PerlUnicodeGeneralCategoryData.resolve("LC").contains('a'));
        assertTrue(PerlUnicodeGeneralCategoryData.resolve("Lo").contains(0x33479));
        assertTrue(PerlUnicodeGeneralCategoryData.resolve("Cn").contains(0x3347a));
        assertFalse(PerlUnicodeGeneralCategoryData.resolve("Cn").contains(0x33479));
        assertNull(PerlUnicodeGeneralCategoryData.resolve("not_a_category"));
    }

    @Test
    void wildcardCandidatesComeFromPinnedPerlAliases() {
        var result = UnicodeResolver.resolveJoniProperty(
                "General_Category=:\\Aletter\\z:", false);
        assertTrue(result != null);
        assertTrue(contains(result.ranges, 'A'));
        assertTrue(contains(result.ranges, 0x20000));
    }

    private static boolean contains(int[] ranges, int codePoint) {
        if (ranges == null) return false;
        for (int index = 1; index + 1 < ranges.length; index += 2) {
            if (ranges[index] <= codePoint && codePoint <= ranges[index + 1]) return true;
        }
        return false;
    }
}
