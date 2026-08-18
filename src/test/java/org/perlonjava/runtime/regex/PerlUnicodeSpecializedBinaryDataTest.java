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
class PerlUnicodeSpecializedBinaryDataTest {
    @Test
    void usesChecksumPinnedPerl544Unicode17Sources() {
        assertEquals("17.0.0", PerlUnicodeSpecializedBinaryData.UNICODE_VERSION);
        assertEquals("130dcddcaadaf071008bdfce1e7743e04fdfbc910886f017d9f9ac931d8c64dd",
                PerlUnicodeSpecializedBinaryData.PROP_LIST_SHA256);
        assertEquals("76a3081265e6eb673873f9c93d6f36062e82c7ed027c5c1a592accfbe48c20a5",
                PerlUnicodeSpecializedBinaryData.UNIKEMET_SHA256);
        assertEquals("670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01",
                PerlUnicodeSpecializedBinaryData.PROP_VALUE_ALIASES_SHA256);
        assertEquals("4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb",
                PerlUnicodeSpecializedBinaryData.PROPERTY_ALIASES_SHA256);
    }

    @Test
    void retainsExactOfficialNamesAndLooseAliases() {
        String[] names = PerlUnicodeSpecializedBinaryData.canonicalNames();
        assertEquals(7, names.length);
        assertEquals("Hyphen", names[0]);
        assertEquals("kEH_NoMirror", names[1]);
        assertEquals("kEH_NoRotate", names[2]);
        assertEquals("ID_Compat_Math_Continue", names[3]);
        assertEquals("ID_Compat_Math_Start", names[4]);
        assertEquals("IDS_Unary_Operator", names[5]);
        assertEquals("Modifier_Combining_Mark", names[6]);
        assertEquals("IDSU", PerlUnicodeSpecializedBinaryData.shortName(
                "ids unary operator"));
        assertEquals("MCM", PerlUnicodeSpecializedBinaryData.shortName(
                "modifier-combining_mark"));
        assertSame(PerlUnicodeSpecializedBinaryData.yesSet("IDSU"),
                PerlUnicodeSpecializedBinaryData.yesSet("IDS_Unary_Operator"));
        assertSame(PerlUnicodeSpecializedBinaryData.yesSet("MCM"),
                PerlUnicodeSpecializedBinaryData.yesSet("Modifier_Combining_Mark"));
        assertTrue(PerlUnicodeSpecializedBinaryData.isPropertyAlias("k e h-no_rotate"));
        assertFalse(PerlUnicodeSpecializedBinaryData.isPropertyAlias("kEH_Cat"));
        assertNull(PerlUnicodeSpecializedBinaryData.yesSet(null));
    }

    @Test
    void mapsPinnedPositiveSetsAndCounts() {
        assertEquals(11, PerlUnicodeSpecializedBinaryData.yesSet("Hyphen").size());
        assertEquals(4, PerlUnicodeSpecializedBinaryData.yesSet("kEH_NoMirror").size());
        assertEquals(44, PerlUnicodeSpecializedBinaryData.yesSet("kEH_NoRotate").size());
        assertEquals(43, PerlUnicodeSpecializedBinaryData.yesSet(
                "ID_Compat_Math_Continue").size());
        assertEquals(13, PerlUnicodeSpecializedBinaryData.yesSet(
                "ID_Compat_Math_Start").size());
        assertEquals(2, PerlUnicodeSpecializedBinaryData.yesSet("IDSU").size());
        assertEquals(14, PerlUnicodeSpecializedBinaryData.yesSet("MCM").size());
        assertTrue(PerlUnicodeSpecializedBinaryData.yesSet("Hyphen").contains(0xFF65));
        assertTrue(PerlUnicodeSpecializedBinaryData.yesSet("kEH_NoMirror").contains(0x13081));
        assertTrue(PerlUnicodeSpecializedBinaryData.yesSet("kEH_NoRotate").contains(0x143E8));
        assertTrue(PerlUnicodeSpecializedBinaryData.yesSet("ID_Compat_Math_Continue")
                .contains(0x00B2));
        assertTrue(PerlUnicodeSpecializedBinaryData.yesSet("ID_Compat_Math_Start")
                .contains(0x2202));
        assertTrue(PerlUnicodeSpecializedBinaryData.yesSet("IDSU").contains(0x2FFF));
        assertTrue(PerlUnicodeSpecializedBinaryData.yesSet("MCM").contains(0x0654));
    }

    @Test
    void recordsPerlPolicyCategoriesWithoutAcceptingUnrelatedIcuNames() {
        assertTrue(PerlUnicodeSpecializedBinaryData.isDeprecated("Hyphen"));
        assertFalse(PerlUnicodeSpecializedBinaryData.isDeprecated("IDSU"));
        assertTrue(PerlUnicodeSpecializedBinaryData.isContributory("MCM"));
        assertTrue(PerlUnicodeSpecializedBinaryData.isContributory("kEH_NoMirror"));
        assertTrue(PerlUnicodeSpecializedBinaryData.isNewInUnicode17("kEH_NoRotate"));
        assertTrue(PerlUnicodeSpecializedBinaryData.isNewInUnicode17("IDSU"));
        assertFalse(PerlUnicodeSpecializedBinaryData.isNewInUnicode17(
                "ID_Compat_Math_Start"));
        assertNull(PerlUnicodeSpecializedBinaryData.valueSet("Emoji", "Yes"));
        assertNull(PerlUnicodeSpecializedBinaryData.valueSet("Hyphen", "Maybe"));
    }

    @Test
    void binaryAliasesProduceCompleteDisjointFrozenPartitions() {
        for (String property : PerlUnicodeSpecializedBinaryData.canonicalNames()) {
            UnicodeSet yes = PerlUnicodeSpecializedBinaryData.valueSet(property, "Y");
            assertSame(yes, PerlUnicodeSpecializedBinaryData.valueSet(property, "True"));
            UnicodeSet no = PerlUnicodeSpecializedBinaryData.valueSet(property, "n-o");
            assertNotNull(no, property);
            assertTrue(yes.isFrozen(), property);
            assertTrue(no.isFrozen(), property);
            assertTrue(new UnicodeSet(yes).retainAll(no).isEmpty(), property);
            UnicodeSet union = new UnicodeSet(yes).addAll(no);
            assertEquals(0x110000, union.size(), property);
            assertTrue(union.contains(0, 0x10FFFF), property);
        }
    }
}
