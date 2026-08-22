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
    void usesCurrentPerl544Unicode17Sources() {
        assertEquals("17.0.0", PerlUnicodeIndicCategoryData.UNICODE_VERSION);
        assertEquals("3fc122f4cf58b0c19268d5f810263b04ab4e1e67743386ec0e0ada9c76aec5be",
                PerlUnicodeIndicCategoryData.INDIC_SYLLABIC_CATEGORY_SHA256);
        assertEquals("68cedc29a7e57f984d90fe2c7712f2e6d0c717e253db219607daea8997d6c480",
                PerlUnicodeIndicCategoryData.INDIC_POSITIONAL_CATEGORY_SHA256);
        assertEquals("670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01",
                PerlUnicodeIndicCategoryData.PROP_VALUE_ALIASES_SHA256);
        assertEquals("4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb",
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
