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
    void usesChecksumPinnedCurrentUnicodeSources() {
        assertEquals("18.0.0", PerlUnicodeJoiningGroupData.UNICODE_VERSION);
        assertEquals("325be8cd3a32a9cc2b80a1fe67e5c7ade3b58d8280fa9d49134edabed82832a1",
                PerlUnicodeJoiningGroupData.DJOIN_GROUP_SHA256);
        assertEquals("06c4c8eaf7b0bf34abe73b113da1215bd784ac254d4c223600b90267caa4bbbd",
                PerlUnicodeJoiningGroupData.PROP_VALUE_ALIASES_SHA256);
        assertEquals("83b8df695f9da543dba02b0be2b8bd72f0b52836ad264be113c6d285021ef025",
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
        assertEquals(116, values.length);
        for (String value : values) {
            UnicodeSet set = PerlUnicodeJoiningGroupData.valueSet(value);
            assertNotNull(set, value);
            assertTrue(set.isFrozen(), value);
            assertFalse(set.isEmpty(), value);
            assertEquals(value, PerlUnicodeJoiningGroupData.shortValue(value), value);
        }

        String[] wildcardValues = PerlUnicodeJoiningGroupData.wildcardValues();
        assertEquals(117, wildcardValues.length);
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
