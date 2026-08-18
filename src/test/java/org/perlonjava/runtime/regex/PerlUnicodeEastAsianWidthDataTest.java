package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.icu.text.UnicodeSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeEastAsianWidthDataTest {
    private static final String[][] VALUES = {
        {"A",  "Ambiguous",  "00A1"},
        {"F",  "Fullwidth",  "FF01"},
        {"H",  "Halfwidth",  "FF61"},
        {"N",  "Neutral",    "0000"},
        {"Na", "Narrow",     "0020"},
        {"W",  "Wide",       "1100"},
    };

    @Test
    void usesChecksumPinnedPerl544Unicode17Sources() {
        assertEquals("17.0.0", PerlUnicodeEastAsianWidthData.UNICODE_VERSION);
        assertEquals("0b5523a2217cb318d20b329a05d31eec5af5686ba09d263b85bb75a28989a3a8",
                PerlUnicodeEastAsianWidthData.DEAST_ASIAN_WIDTH_SHA256);
        assertEquals("670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01",
                PerlUnicodeEastAsianWidthData.PROP_VALUE_ALIASES_SHA256);
        assertEquals("4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb",
                PerlUnicodeEastAsianWidthData.PROPERTY_ALIASES_SHA256);
    }

    @Test
    void acceptsPinnedPropertyAndLooseValueAliases() {
        assertTrue(PerlUnicodeEastAsianWidthData.isPropertyAlias("ea"));
        assertTrue(PerlUnicodeEastAsianWidthData.isPropertyAlias("East Asian Width"));
        assertTrue(PerlUnicodeEastAsianWidthData.isPropertyAlias("EAST-ASIAN_WIDTH"));
        assertFalse(PerlUnicodeEastAsianWidthData.isPropertyAlias("Emoji Width"));

        UnicodeSet ambiguous = PerlUnicodeEastAsianWidthData.valueSet("A");
        assertSame(ambiguous, PerlUnicodeEastAsianWidthData.valueSet("Ambiguous"));
        assertSame(ambiguous, PerlUnicodeEastAsianWidthData.valueSet("a-m_b i g u o u s"));
        assertEquals("A", PerlUnicodeEastAsianWidthData.shortValue("ambiguous"));
        assertEquals("Ambiguous", PerlUnicodeEastAsianWidthData.canonicalValue("a"));
        assertNull(PerlUnicodeEastAsianWidthData.valueSet("not a width"));
        assertNull(PerlUnicodeEastAsianWidthData.canonicalValue(null));
    }

    @Test
    void retainsEveryPinnedValue() {
        assertEquals(6, PerlUnicodeEastAsianWidthData.canonicalValues().length);
        for (String[] value : VALUES) {
            UnicodeSet shortSet = PerlUnicodeEastAsianWidthData.valueSet(value[0]);
            assertNotNull(shortSet, value[0]);
            assertTrue(shortSet.isFrozen(), value[0]);
            assertSame(shortSet, PerlUnicodeEastAsianWidthData.valueSet(value[1]), value[0]);
            assertTrue(shortSet.contains(Integer.parseInt(value[2], 16)), value[0]);
        }
    }

    @Test
    void appliesEveryOrderedMissingDefaultBeforeExplicitRanges() {
        assertTrue(PerlUnicodeEastAsianWidthData.valueSet("W").contains(0x3FF0));
        assertTrue(PerlUnicodeEastAsianWidthData.valueSet("W").contains(0x2FA20));
        assertTrue(PerlUnicodeEastAsianWidthData.valueSet("W").contains(0x3FFFD));
        assertTrue(PerlUnicodeEastAsianWidthData.valueSet("N").contains(0x0378));
        assertTrue(PerlUnicodeEastAsianWidthData.valueSet("N").contains(0x10FFFF));
        assertTrue(PerlUnicodeEastAsianWidthData.valueSet("F").contains(0xFF01));
    }

    @Test
    void formsOneDisjointPartitionOfAllUnicodeCodePoints() {
        String[] values = PerlUnicodeEastAsianWidthData.canonicalValues();
        UnicodeSet union = new UnicodeSet();
        for (int i = 0; i < values.length; i++) {
            UnicodeSet current = PerlUnicodeEastAsianWidthData.valueSet(values[i]);
            union.addAll(current);
            for (int j = i + 1; j < values.length; j++) {
                UnicodeSet overlap = new UnicodeSet(current)
                        .retainAll(PerlUnicodeEastAsianWidthData.valueSet(values[j]));
                assertTrue(overlap.isEmpty(), values[i] + " overlaps " + values[j]);
            }
        }
        assertEquals(0x110000, union.size());
        assertTrue(union.contains(0, 0x10FFFF));
    }
}
