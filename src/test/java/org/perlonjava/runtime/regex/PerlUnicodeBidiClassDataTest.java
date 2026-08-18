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
class PerlUnicodeBidiClassDataTest {
    private static final String[][] VALUES = {
        {"AL", "Arabic_Letter", "0608"},
        {"AN", "Arabic_Number", "0600"},
        {"B", "Paragraph_Separator", "000A"},
        {"BN", "Boundary_Neutral", "0000"},
        {"CS", "Common_Separator", "002C"},
        {"EN", "European_Number", "0030"},
        {"ES", "European_Separator", "002B"},
        {"ET", "European_Terminator", "0023"},
        {"FSI", "First_Strong_Isolate", "2068"},
        {"L", "Left_To_Right", "0041"},
        {"LRE", "Left_To_Right_Embedding", "202A"},
        {"LRI", "Left_To_Right_Isolate", "2066"},
        {"LRO", "Left_To_Right_Override", "202D"},
        {"NSM", "Nonspacing_Mark", "0300"},
        {"ON", "Other_Neutral", "0021"},
        {"PDF", "Pop_Directional_Format", "202C"},
        {"PDI", "Pop_Directional_Isolate", "2069"},
        {"R", "Right_To_Left", "05BE"},
        {"RLE", "Right_To_Left_Embedding", "202B"},
        {"RLI", "Right_To_Left_Isolate", "2067"},
        {"RLO", "Right_To_Left_Override", "202E"},
        {"S", "Segment_Separator", "0009"},
        {"WS", "White_Space", "000C"},
    };

    @Test
    void usesChecksumPinnedPerl544Unicode17Sources() {
        assertEquals("17.0.0", PerlUnicodeBidiClassData.UNICODE_VERSION);
        assertEquals("4867b4b7f0731ed1bfcd34cc6251211ff1542541fce0734b6fbda139ee80b3a4",
                PerlUnicodeBidiClassData.DBIDI_CLASS_SHA256);
        assertEquals("670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01",
                PerlUnicodeBidiClassData.PROP_VALUE_ALIASES_SHA256);
        assertEquals("4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb",
                PerlUnicodeBidiClassData.PROPERTY_ALIASES_SHA256);
    }

    @Test
    void acceptsPinnedPropertyAndLooseValueAliases() {
        assertTrue(PerlUnicodeBidiClassData.isPropertyAlias("bc"));
        assertTrue(PerlUnicodeBidiClassData.isPropertyAlias("Bidi Class"));
        assertTrue(PerlUnicodeBidiClassData.isPropertyAlias("BIDI-CLASS"));
        assertFalse(PerlUnicodeBidiClassData.isPropertyAlias("Bidi Mirrored"));

        UnicodeSet arabicLetter = PerlUnicodeBidiClassData.valueSet("AL");
        assertSame(arabicLetter, PerlUnicodeBidiClassData.valueSet("Arabic_Letter"));
        assertSame(arabicLetter, PerlUnicodeBidiClassData.valueSet("a-r_a b i c letter"));
        assertEquals("AL", PerlUnicodeBidiClassData.shortValue("arabic letter"));
        assertEquals("Arabic_Letter", PerlUnicodeBidiClassData.canonicalValue("al"));
        assertNull(PerlUnicodeBidiClassData.valueSet("not a bidi class"));
        assertNull(PerlUnicodeBidiClassData.canonicalValue(null));
    }

    @Test
    void retainsEveryPinnedValueIncludingDirectionalControls() {
        assertEquals(23, PerlUnicodeBidiClassData.canonicalValues().length);
        for (String[] value : VALUES) {
            UnicodeSet shortSet = PerlUnicodeBidiClassData.valueSet(value[0]);
            assertNotNull(shortSet, value[0]);
            assertTrue(shortSet.isFrozen(), value[0]);
            assertSame(shortSet, PerlUnicodeBidiClassData.valueSet(value[1]), value[0]);
            assertTrue(shortSet.contains(Integer.parseInt(value[2], 16)), value[0]);
        }
    }

    @Test
    void appliesOrderedMissingDefaultsBeforeExplicitRanges() {
        assertTrue(PerlUnicodeBidiClassData.valueSet("R").contains(0x0590));
        assertTrue(PerlUnicodeBidiClassData.valueSet("AL").contains(0x088B));
        assertTrue(PerlUnicodeBidiClassData.valueSet("ET").contains(0x20CF));
        assertTrue(PerlUnicodeBidiClassData.valueSet("L").contains(0x0378));
        assertTrue(PerlUnicodeBidiClassData.valueSet("BN").contains(0x10FFFF));
    }

    @Test
    void formsOneDisjointPartitionOfAllUnicodeCodePoints() {
        String[] values = PerlUnicodeBidiClassData.canonicalValues();
        UnicodeSet union = new UnicodeSet();
        for (int i = 0; i < values.length; i++) {
            UnicodeSet current = PerlUnicodeBidiClassData.valueSet(values[i]);
            union.addAll(current);
            for (int j = i + 1; j < values.length; j++) {
                UnicodeSet overlap = new UnicodeSet(current)
                        .retainAll(PerlUnicodeBidiClassData.valueSet(values[j]));
                assertTrue(overlap.isEmpty(), values[i] + " overlaps " + values[j]);
            }
        }
        assertEquals(0x110000, union.size());
        assertTrue(union.contains(0, 0x10FFFF));
    }
}
