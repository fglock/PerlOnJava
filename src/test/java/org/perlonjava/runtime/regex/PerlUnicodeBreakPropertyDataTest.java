package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import com.ibm.icu.text.UnicodeSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeBreakPropertyDataTest {
    @Test
    void usesChecksumPinnedPerl544Unicode17Sources() {
        assertEquals("17.0.0", PerlUnicodeBreakPropertyData.UNICODE_VERSION);
        assertEquals("d6b51d1d2ae5c33b451b7ed994b48f1f4dc62b2272a5831e7fd418514a6bae89",
                PerlUnicodeBreakPropertyData.GCB_SHA256);
        assertEquals("871c0c985ad95125e25b302414065a10839d068970bceb383ecec138f22a0a18",
                PerlUnicodeBreakPropertyData.SB_SHA256);
        assertEquals("72274cac1e6b919507db35655c3e175aa27274668a1ece95c28d2069f2ad9852",
                PerlUnicodeBreakPropertyData.WB_SHA256);
        assertEquals("dad3ef492d198d6f1dde4922b175f7371a27dfe62fce489f3e04807015a4c682",
                PerlUnicodeBreakPropertyData.LB_SHA256);
        assertEquals("670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01",
                PerlUnicodeBreakPropertyData.PROP_VALUE_ALIASES_SHA256);
        assertEquals("4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb",
                PerlUnicodeBreakPropertyData.PROPERTY_ALIASES_SHA256);
        assertEquals(1, PerlUnicodeBreakPropertyData.GCB_MISSING_COUNT);
        assertEquals(1, PerlUnicodeBreakPropertyData.SB_MISSING_COUNT);
        assertEquals(1, PerlUnicodeBreakPropertyData.WB_MISSING_COUNT);
        assertEquals(10, PerlUnicodeBreakPropertyData.LB_MISSING_COUNT);
        assertEquals(1429, PerlUnicodeBreakPropertyData.GCB_EXPLICIT_RANGE_COUNT);
        assertEquals(2930, PerlUnicodeBreakPropertyData.SB_EXPLICIT_RANGE_COUNT);
        assertEquals(1432, PerlUnicodeBreakPropertyData.WB_EXPLICIT_RANGE_COUNT);
        assertEquals(3580, PerlUnicodeBreakPropertyData.LB_EXPLICIT_RANGE_COUNT);
    }

    @Test
    void acceptsPropertySpecificLooseAliases() {
        assertTrue(PerlUnicodeBreakPropertyData.isPropertyAlias("GCB"));
        assertTrue(PerlUnicodeBreakPropertyData.isPropertyAlias(
                "grapheme-cluster break"));
        assertTrue(PerlUnicodeBreakPropertyData.isPropertyAlias("Sentence_Break"));
        assertTrue(PerlUnicodeBreakPropertyData.isPropertyAlias("word break"));
        assertTrue(PerlUnicodeBreakPropertyData.isPropertyAlias("LINE-BREAK"));
        assertFalse(PerlUnicodeBreakPropertyData.isPropertyAlias("break"));
        assertFalse(PerlUnicodeBreakPropertyData.isPropertyAlias(null));

        UnicodeSet gcbExtend = PerlUnicodeBreakPropertyData.valueSet("GCB", "EX");
        assertSame(gcbExtend, PerlUnicodeBreakPropertyData.valueSet(
                "grapheme cluster break", "e-x t_e n d"));
        assertEquals("Extend", PerlUnicodeBreakPropertyData.canonicalValue("GCB", "EX"));
        assertEquals("EX", PerlUnicodeBreakPropertyData.shortValue("GCB", "Extend"));
        assertEquals("Exclamation", PerlUnicodeBreakPropertyData.canonicalValue("LB", "EX"));
        assertEquals("ExtendNumLet", PerlUnicodeBreakPropertyData.canonicalValue("WB", "EX"));
        assertNull(PerlUnicodeBreakPropertyData.valueSet("GCB", "Exclamation"));
        assertNull(PerlUnicodeBreakPropertyData.valueSet("unknown property", "Other"));
        assertNull(PerlUnicodeBreakPropertyData.canonicalValues(null));
    }

    @Test
    void retainsEveryPinnedValueAndRawWildcardAlias() {
        String[] properties = {"GCB", "SB", "WB", "LB"};
        int[] valueCounts = {18, 15, 23, 49};
        int[] wildcardCounts = {28, 28, 41, 93};
        for (int property = 0; property < properties.length; property++) {
            String[] values = PerlUnicodeBreakPropertyData.canonicalValues(properties[property]);
            assertEquals(valueCounts[property], values.length, properties[property]);
            for (String value : values) {
                UnicodeSet set = PerlUnicodeBreakPropertyData.valueSet(properties[property], value);
                assertNotNull(set, properties[property] + '=' + value);
                assertTrue(set.isFrozen(), properties[property] + '=' + value);
                assertNotNull(PerlUnicodeBreakPropertyData.shortValue(
                        properties[property], value), value);
            }
            String[] wildcards = PerlUnicodeBreakPropertyData.wildcardValues(properties[property]);
            assertEquals(wildcardCounts[property], wildcards.length, properties[property]);
            for (String wildcard : wildcards) {
                assertNotNull(PerlUnicodeBreakPropertyData.valueSet(
                        properties[property], wildcard), properties[property] + '=' + wildcard);
            }
        }
        assertTrue(Arrays.asList(PerlUnicodeBreakPropertyData.wildcardValues("LB"))
                .contains("Inseperable"));
        assertSame(PerlUnicodeBreakPropertyData.valueSet("LB", "Inseparable"),
                PerlUnicodeBreakPropertyData.valueSet("LB", "Inseperable"));
    }

    @Test
    void mapsRepresentativeExplicitAndDefaultValues() {
        assertTrue(PerlUnicodeBreakPropertyData.valueSet("GCB", "Extend").contains(0x0300));
        assertTrue(PerlUnicodeBreakPropertyData.valueSet("GCB", "Other").contains(0x0041));
        assertTrue(PerlUnicodeBreakPropertyData.valueSet("SB", "Upper").contains(0x0041));
        assertTrue(PerlUnicodeBreakPropertyData.valueSet("SB", "ATerm").contains(0x002E));
        assertTrue(PerlUnicodeBreakPropertyData.valueSet("WB", "ALetter").contains(0x0041));
        assertTrue(PerlUnicodeBreakPropertyData.valueSet("WB", "Extend").contains(0x0300));
        assertTrue(PerlUnicodeBreakPropertyData.valueSet("LB", "Alphabetic").contains(0x0041));
        assertTrue(PerlUnicodeBreakPropertyData.valueSet("LB", "Prefix_Numeric")
                .contains(0x20C1));
        assertTrue(PerlUnicodeBreakPropertyData.valueSet("LB", "Ideographic")
                .contains(0x3401));
        assertTrue(PerlUnicodeBreakPropertyData.valueSet("LB", "Ideographic")
                .contains(0x1F02C));
        assertTrue(PerlUnicodeBreakPropertyData.valueSet("LB", "Unknown").contains(0x40000));
    }

    @Test
    void everyBreakPropertyFormsOneCompleteDisjointPartition() {
        for (String property : new String[] {"GCB", "SB", "WB", "LB"}) {
            String[] values = PerlUnicodeBreakPropertyData.canonicalValues(property);
            UnicodeSet union = new UnicodeSet();
            for (int i = 0; i < values.length; i++) {
                UnicodeSet set = PerlUnicodeBreakPropertyData.valueSet(property, values[i]);
                union.addAll(set);
                for (int j = i + 1; j < values.length; j++) {
                    UnicodeSet overlap = new UnicodeSet(set).retainAll(
                            PerlUnicodeBreakPropertyData.valueSet(property, values[j]));
                    assertTrue(overlap.isEmpty(), property + ": "
                            + values[i] + " overlaps " + values[j]);
                }
            }
            assertEquals(0x110000, union.size(), property);
            assertTrue(union.contains(0, 0x10FFFF), property);
        }
    }
}
