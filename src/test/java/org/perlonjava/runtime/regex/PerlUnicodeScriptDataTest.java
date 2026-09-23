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
class PerlUnicodeScriptDataTest {
    @Test
    void usesChecksumPinnedPerl545Unicode18Sources() {
        assertEquals("18.0.0", PerlUnicodeScriptData.UNICODE_VERSION);
        assertEquals("0071fd81b6aeae25f6e8bce8efec3066a6476a91b49bdb2f52dc76e817862a6a",
                PerlUnicodeScriptData.SCRIPTS_SHA256);
        assertEquals("5c9d34a922f687726f2a8bcf57d49f905987e51f1b21b58c95a00fbe255cec23",
                PerlUnicodeScriptData.SCRIPT_EXTENSIONS_SHA256);
        assertEquals("06c4c8eaf7b0bf34abe73b113da1215bd784ac254d4c223600b90267caa4bbbd",
                PerlUnicodeScriptData.PROP_VALUE_ALIASES_SHA256);
        assertEquals("83b8df695f9da543dba02b0be2b8bd72f0b52836ad264be113c6d285021ef025",
                PerlUnicodeScriptData.PROPERTY_ALIASES_SHA256);
    }

    @Test
    void acceptsPinnedPropertyAndLooseValueAliases() {
        assertTrue(PerlUnicodeScriptData.isScriptPropertyAlias("sc"));
        assertTrue(PerlUnicodeScriptData.isScriptPropertyAlias("Script"));
        assertTrue(PerlUnicodeScriptData.isScriptExtensionsPropertyAlias("scx"));
        assertTrue(PerlUnicodeScriptData.isScriptExtensionsPropertyAlias(
                "SCRIPT-EXTENSIONS"));
        assertFalse(PerlUnicodeScriptData.isScriptPropertyAlias("scx"));
        assertFalse(PerlUnicodeScriptData.isScriptExtensionsPropertyAlias("Script"));

        UnicodeSet caucasianAlbanian = PerlUnicodeScriptData.scriptSet("Aghb");
        assertSame(caucasianAlbanian,
                PerlUnicodeScriptData.scriptSet("Caucasian_Albanian"));
        assertSame(caucasianAlbanian,
                PerlUnicodeScriptData.scriptSet("c-a u_c a s i a n albanian"));
        assertEquals("Aghb", PerlUnicodeScriptData.shortValue("caucasian albanian"));
        assertEquals("Caucasian_Albanian",
                PerlUnicodeScriptData.canonicalValue("AGHB"));
        assertNull(PerlUnicodeScriptData.scriptSet("not a script"));
        assertNull(PerlUnicodeScriptData.scriptExtensionsSet(null));
    }

    @Test
    void retainsEveryPinnedValueAndWildcardAlias() {
        String[] values = PerlUnicodeScriptData.canonicalValues();
        assertEquals(179, values.length);
        for (String value : values) {
            UnicodeSet script = PerlUnicodeScriptData.scriptSet(value);
            UnicodeSet extensions = PerlUnicodeScriptData.scriptExtensionsSet(value);
            assertNotNull(script, value);
            assertNotNull(extensions, value);
            assertTrue(script.isFrozen(), value);
            assertTrue(extensions.isFrozen(), value);
            assertNotNull(PerlUnicodeScriptData.shortValue(value), value);
        }

        String[] wildcardValues = PerlUnicodeScriptData.wildcardValues();
        assertEquals(351, wildcardValues.length);
        for (String value : wildcardValues) {
            assertNotNull(PerlUnicodeScriptData.scriptSet(value), value);
            assertNotNull(PerlUnicodeScriptData.scriptExtensionsSet(value), value);
        }
        assertTrue(Arrays.asList(wildcardValues).contains("Qaac"));
        assertTrue(Arrays.asList(wildcardValues).contains("Qaai"));
    }

    @Test
    void mapsAlternateAndSpecialScriptValues() {
        assertSame(PerlUnicodeScriptData.scriptSet("Coptic"),
                PerlUnicodeScriptData.scriptSet("Qaac"));
        assertSame(PerlUnicodeScriptData.scriptSet("Inherited"),
                PerlUnicodeScriptData.scriptSet("Qaai"));
        assertTrue(PerlUnicodeScriptData.scriptSet("Common").contains(0x0030));
        assertTrue(PerlUnicodeScriptData.scriptSet("Inherited").contains(0x0300));
        assertTrue(PerlUnicodeScriptData.scriptSet("Unknown").contains(0x0378));
        assertEquals("Zyyy", PerlUnicodeScriptData.shortValue("Common"));
        assertEquals("Zinh", PerlUnicodeScriptData.shortValue("Inherited"));
        assertEquals("Zzzz", PerlUnicodeScriptData.shortValue("Unknown"));
    }

    @Test
    void appliesScriptExtensionsOverridesAndScriptFallback() {
        assertTrue(PerlUnicodeScriptData.scriptSet("Common").contains(0x00B7));
        assertFalse(PerlUnicodeScriptData.scriptExtensionsSet("Common").contains(0x00B7));
        assertTrue(PerlUnicodeScriptData.scriptExtensionsSet("Latin").contains(0x00B7));
        assertTrue(PerlUnicodeScriptData.scriptExtensionsSet("Greek").contains(0x00B7));
        assertEquals(16, extensionMembershipCount(0x00B7));

        assertTrue(PerlUnicodeScriptData.scriptSet("Inherited").contains(0x0300));
        assertFalse(PerlUnicodeScriptData.scriptExtensionsSet("Inherited").contains(0x0300));
        assertTrue(PerlUnicodeScriptData.scriptExtensionsSet("Latin").contains(0x0300));
        assertEquals(8, extensionMembershipCount(0x0300));

        assertTrue(PerlUnicodeScriptData.scriptSet("Latin").contains(0x0041));
        assertTrue(PerlUnicodeScriptData.scriptExtensionsSet("Latin").contains(0x0041));
        assertEquals(1, extensionMembershipCount(0x0041));
        assertTrue(PerlUnicodeScriptData.scriptExtensionsSet("Unknown").contains(0x0378));
        assertEquals(1, extensionMembershipCount(0x0378));
    }

    @Test
    void scriptFormsOneDisjointPartitionAndExtensionsCoverUnicode() {
        String[] values = PerlUnicodeScriptData.canonicalValues();
        UnicodeSet scriptUnion = new UnicodeSet();
        UnicodeSet extensionsUnion = new UnicodeSet();
        for (int i = 0; i < values.length; i++) {
            UnicodeSet script = PerlUnicodeScriptData.scriptSet(values[i]);
            scriptUnion.addAll(script);
            extensionsUnion.addAll(PerlUnicodeScriptData.scriptExtensionsSet(values[i]));
            for (int j = i + 1; j < values.length; j++) {
                UnicodeSet overlap = new UnicodeSet(script)
                        .retainAll(PerlUnicodeScriptData.scriptSet(values[j]));
                assertTrue(overlap.isEmpty(), values[i] + " overlaps " + values[j]);
            }
        }
        assertEquals(0x110000, scriptUnion.size());
        assertTrue(scriptUnion.contains(0, 0x10FFFF));
        assertEquals(0x110000, extensionsUnion.size());
        assertTrue(extensionsUnion.contains(0, 0x10FFFF));
    }

    private static int extensionMembershipCount(int codePoint) {
        int count = 0;
        for (String value : PerlUnicodeScriptData.canonicalValues()) {
            if (PerlUnicodeScriptData.scriptExtensionsSet(value).contains(codePoint)) count++;
        }
        return count;
    }
}
