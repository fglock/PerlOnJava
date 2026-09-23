package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.icu.text.UnicodeSet;
import org.joni.CharacterPropertyResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeIndicCategoryDataTest {
    @Test
    void usesCurrentPerl545Unicode18Sources() {
        assertEquals("18.0.0", PerlUnicodeIndicCategoryData.UNICODE_VERSION);
        assertEquals("a2b3aacf6b3e7bad4ca351ef985d9543825e20280ff280c25f646d9bc4ce304c",
                PerlUnicodeIndicCategoryData.INDIC_SYLLABIC_CATEGORY_SHA256);
        assertEquals("c9ad44d267c317ee0e1ec66cbd4d15033c2964f905d11fc8905915f9a5a9ae52",
                PerlUnicodeIndicCategoryData.INDIC_POSITIONAL_CATEGORY_SHA256);
        assertEquals("06c4c8eaf7b0bf34abe73b113da1215bd784ac254d4c223600b90267caa4bbbd",
                PerlUnicodeIndicCategoryData.PROP_VALUE_ALIASES_SHA256);
        assertEquals("83b8df695f9da543dba02b0be2b8bd72f0b52836ad264be113c6d285021ef025",
                PerlUnicodeIndicCategoryData.PROPERTY_ALIASES_SHA256);
    }

    @Test
    void acceptsPropertyAndLooseValueAliases() {
        assertTrue(PerlUnicodeIndicCategoryData.isPropertyAlias("InSC"));
        assertTrue(PerlUnicodeIndicCategoryData.isPropertyAlias(
                "indic syllabic-category"));
        assertTrue(PerlUnicodeIndicCategoryData.isPropertyAlias("InPC"));
        assertTrue(PerlUnicodeIndicCategoryData.isPropertyAlias(
                "INDIC_POSITIONAL CATEGORY"));
        assertFalse(PerlUnicodeIndicCategoryData.isPropertyAlias("Script"));

        UnicodeSet vowel = PerlUnicodeIndicCategoryData.valueSet(
                "InSC", "Vowel_Dependent");
        assertSame(vowel, PerlUnicodeIndicCategoryData.valueSet(
                "Indic-Syllabic Category", "vowel dependent"));
        assertEquals("Vowel_Dependent",
                PerlUnicodeIndicCategoryData.canonicalValue(
                        "InSC", "v-o_w e l dependent"));
        assertEquals("Not_Applicable",
                PerlUnicodeIndicCategoryData.canonicalValue("InPC", "NA"));
        assertNull(PerlUnicodeIndicCategoryData.valueSet("InSC", "unknown"));
        assertNull(PerlUnicodeIndicCategoryData.valueSet("unknown", "Other"));
    }

    @Test
    void retainsEveryValueAndAppliesMissingDefaults() {
        assertEquals(37,
                PerlUnicodeIndicCategoryData.canonicalValues("InSC").length);
        assertEquals(16,
                PerlUnicodeIndicCategoryData.canonicalValues("InPC").length);
        assertEquals("Other",
                PerlUnicodeIndicCategoryData.defaultValue("InSC"));
        assertEquals("Not_Applicable",
                PerlUnicodeIndicCategoryData.defaultValue("InPC"));

        assertTrue(PerlUnicodeIndicCategoryData.valueSet(
                "InSC", "Vowel_Dependent").contains(0x093E));
        assertTrue(PerlUnicodeIndicCategoryData.valueSet(
                "InPC", "Bottom").contains(0x093C));
        assertTrue(PerlUnicodeIndicCategoryData.valueSet(
                "InSC", "Other").contains('A'));
        assertTrue(PerlUnicodeIndicCategoryData.valueSet(
                "InPC", "NA").contains('A'));
    }

    @Test
    void eachPropertyFormsOneDisjointUnicodePartition() {
        assertPartition("InSC");
        assertPartition("InPC");
    }

    @Test
    void resolvesThroughTheNativeJoniHookWithoutCaseFolding() {
        CharacterPropertyResolver.Result syllabic =
                UnicodeResolver.resolveJoniProperty(
                        "InSC=Vowel_Dependent", false, true);
        CharacterPropertyResolver.Result positional =
                UnicodeResolver.resolveJoniProperty(
                        "Indic_Positional_Category=Bottom", true, true);
        assertNotNull(syllabic);
        assertNotNull(positional);
        assertFalse(syllabic.caseFold);
        assertFalse(positional.caseFold);
        assertTrue(contains(syllabic.ranges, 0x093E));
        assertTrue(contains(positional.ranges, 0x093C));
        assertFalse(contains(syllabic.ranges, 'A'));
        assertFalse(contains(positional.ranges, 'A'));
    }

    private static void assertPartition(String property) {
        String[] values = PerlUnicodeIndicCategoryData.canonicalValues(property);
        UnicodeSet union = new UnicodeSet();
        for (int i = 0; i < values.length; i++) {
            UnicodeSet current = PerlUnicodeIndicCategoryData.valueSet(property, i);
            assertNotNull(current, values[i]);
            assertTrue(current.isFrozen(), values[i]);
            union.addAll(current);
            for (int j = i + 1; j < values.length; j++) {
                UnicodeSet overlap = new UnicodeSet(current).retainAll(
                        PerlUnicodeIndicCategoryData.valueSet(property, j));
                assertTrue(overlap.isEmpty(), values[i] + " overlaps " + values[j]);
            }
        }
        assertEquals(0x110000, union.size());
        assertTrue(union.contains(0, 0x10FFFF));
    }

    private static boolean contains(int[] ranges, int codePoint) {
        if (ranges == null) return false;
        for (int i = 1; i + 1 < ranges.length; i += 2) {
            if (ranges[i] <= codePoint && codePoint <= ranges[i + 1]) return true;
        }
        return false;
    }
}
