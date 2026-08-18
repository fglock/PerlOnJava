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
    void usesPinnedPerl544Unicode17DataAndAliases() {
        assertEquals("17.0.0", PerlUnicodeGeneralCategoryData.UNICODE_VERSION);
        assertTrue(PerlUnicodeGeneralCategoryData.resolve("uppercase_letter").contains('A'));
        assertTrue(PerlUnicodeGeneralCategoryData.resolve(":\\ALu\\z:").contains('A'));
        assertTrue(PerlUnicodeGeneralCategoryData.resolve("L").contains('A'));
        assertTrue(PerlUnicodeGeneralCategoryData.resolve("LC").contains('a'));
        assertTrue(PerlUnicodeGeneralCategoryData.resolve("Lo").contains(0x33479));
        assertTrue(PerlUnicodeGeneralCategoryData.resolve("Cn").contains(0x3347a));
        assertFalse(PerlUnicodeGeneralCategoryData.resolve("Cn").contains(0x33479));
        assertNull(PerlUnicodeGeneralCategoryData.resolve("not_a_category"));
    }
}
