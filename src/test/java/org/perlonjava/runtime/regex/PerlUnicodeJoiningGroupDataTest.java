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
class PerlUnicodeJoiningGroupDataTest {
    @Test
    void usesChecksumPinnedPerl544Unicode17Sources() {
        assertEquals("17.0.0", PerlUnicodeJoiningGroupData.UNICODE_VERSION);
        assertEquals("bb67e0c00b88acfa5be633967b66b23326844a86e49c6fde7b57960d3af66cae",
                PerlUnicodeJoiningGroupData.DJOIN_GROUP_SHA256);
        assertEquals("670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01",
                PerlUnicodeJoiningGroupData.PROP_VALUE_ALIASES_SHA256);
        assertEquals("4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb",
                PerlUnicodeJoiningGroupData.PROPERTY_ALIASES_SHA256);
    }

    @Test
    void acceptsPinnedPropertyAndLooseValueAliases() {
        assertTrue(PerlUnicodeJoiningGroupData.isPropertyAlias("jg"));
        assertTrue(PerlUnicodeJoiningGroupData.isPropertyAlias("Joining Group"));
        assertTrue(PerlUnicodeJoiningGroupData.isPropertyAlias("JOINING-GROUP"));
        assertFalse(PerlUnicodeJoiningGroupData.isPropertyAlias("Joining Type"));

        UnicodeSet africanFeh = PerlUnicodeJoiningGroupData.valueSet("African_Feh");
        assertSame(africanFeh,
                PerlUnicodeJoiningGroupData.valueSet("a-f r_i c a n feh"));
        assertEquals("African_Feh",
                PerlUnicodeJoiningGroupData.shortValue("african feh"));
        assertEquals("African_Feh",
                PerlUnicodeJoiningGroupData.canonicalValue("AFRICAN-FEH"));
        assertNull(PerlUnicodeJoiningGroupData.valueSet("not a joining group"));
        assertNull(PerlUnicodeJoiningGroupData.canonicalValue(null));
    }

    @Test
    void retainsEveryPinnedValueAndWildcardAlias() {
        String[] values = PerlUnicodeJoiningGroupData.canonicalValues();
        assertEquals(106, values.length);
        for (String value : values) {
            UnicodeSet set = PerlUnicodeJoiningGroupData.valueSet(value);
            assertNotNull(set, value);
            assertTrue(set.isFrozen(), value);
            assertFalse(set.isEmpty(), value);
            assertEquals(value, PerlUnicodeJoiningGroupData.shortValue(value), value);
        }

        String[] wildcardValues = PerlUnicodeJoiningGroupData.wildcardValues();
        assertEquals(107, wildcardValues.length);
        for (String value : wildcardValues) {
            assertNotNull(PerlUnicodeJoiningGroupData.valueSet(value), value);
        }
        assertTrue(Arrays.asList(wildcardValues).contains("Hamza_On_Heh_Goal"));
    }

    @Test
    void mapsTheAlternateTehMarbutaGoalAlias() {
        UnicodeSet canonical = PerlUnicodeJoiningGroupData.valueSet("Teh_Marbuta_Goal");
        assertSame(canonical, PerlUnicodeJoiningGroupData.valueSet("Hamza_On_Heh_Goal"));
        assertEquals("Teh_Marbuta_Goal",
                PerlUnicodeJoiningGroupData.canonicalValue("hamza on heh goal"));
        assertTrue(canonical.contains(0x06C3));
    }

    @Test
    void appliesNoJoiningGroupDefaultBeforeExplicitRanges() {
        UnicodeSet none = PerlUnicodeJoiningGroupData.valueSet("No_Joining_Group");
        assertTrue(none.contains(0x0000));
        assertTrue(none.contains(0x0378));
        assertTrue(none.contains(0x10FFFF));
        assertFalse(none.contains(0x0639));
        assertTrue(PerlUnicodeJoiningGroupData.valueSet("Ain").contains(0x0639));
    }

    @Test
    void formsOneDisjointPartitionOfAllUnicodeCodePoints() {
        String[] values = PerlUnicodeJoiningGroupData.canonicalValues();
        UnicodeSet union = new UnicodeSet();
        for (int i = 0; i < values.length; i++) {
            UnicodeSet current = PerlUnicodeJoiningGroupData.valueSet(values[i]);
            union.addAll(current);
            for (int j = i + 1; j < values.length; j++) {
                UnicodeSet overlap = new UnicodeSet(current)
                        .retainAll(PerlUnicodeJoiningGroupData.valueSet(values[j]));
                assertTrue(overlap.isEmpty(), values[i] + " overlaps " + values[j]);
            }
        }
        assertEquals(0x110000, union.size());
        assertTrue(union.contains(0, 0x10FFFF));
    }
}
