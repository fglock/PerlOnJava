package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
class PerlUnicodeVerticalOrientationDataTest {
    @Test
    void usesCurrentPerlUnicode18Sources() {
        assertEquals("18.0.0", PerlUnicodeVerticalOrientationData.UNICODE_VERSION);
        assertEquals("0803e09669d7aa7137de678e55b848bd418c6632694fa1b05f0b40941103c748",
                PerlUnicodeVerticalOrientationData.VERTICAL_ORIENTATION_SHA256);
        assertEquals("06c4c8eaf7b0bf34abe73b113da1215bd784ac254d4c223600b90267caa4bbbd",
                PerlUnicodeVerticalOrientationData.PROP_VALUE_ALIASES_SHA256);
        assertEquals("83b8df695f9da543dba02b0be2b8bd72f0b52836ad264be113c6d285021ef025",
                PerlUnicodeVerticalOrientationData.PROPERTY_ALIASES_SHA256);
    }

    @Test
    void acceptsGeneratedPropertyAndValueAliases() {
        assertTrue(PerlUnicodeVerticalOrientationData.isPropertyAlias("vo"));
        assertTrue(PerlUnicodeVerticalOrientationData.isPropertyAlias(
                "Vertical_Orientation"));
        assertSame(PerlUnicodeVerticalOrientationData.valueSet("R"),
                PerlUnicodeVerticalOrientationData.valueSet("Rotated"));
        assertSame(PerlUnicodeVerticalOrientationData.valueSet("Tr"),
                PerlUnicodeVerticalOrientationData.valueSet("Transformed_Rotated"));
        assertEquals("Transformed_Upright",
                PerlUnicodeVerticalOrientationData.canonicalValue("Tu"));
        assertNull(PerlUnicodeVerticalOrientationData.valueSet("unknown"));
    }

    @Test
    void eachGeneratedAliasResolvesAsAnAnchoredWildcard() {
        String[] canonicalValues = PerlUnicodeVerticalOrientationData.canonicalValues();
        assertEquals(4, canonicalValues.length);
        for (int index = 0; index < canonicalValues.length; index++) {
            UnicodeSet expected = PerlUnicodeVerticalOrientationData.valueSet(
                    canonicalValues[index]);
            String[] aliases = PerlUnicodeVerticalOrientationData.wildcardAliases(index);
            assertTrue(aliases.length >= 2, canonicalValues[index]);
            for (String alias : aliases) {
                CharacterPropertyResolver.Result result =
                        UnicodeResolver.resolveJoniProperty(
                                "vo=:\\A" + alias + "\\z:", false);
                assertNotNull(result, "wildcard alias " + alias);
                assertFalse(result.caseFold, alias);
                assertEquals(expected, toUnicodeSet(result.ranges), alias);
            }
        }
    }

    @Test
    void generatedValuesFormOnePartitionAndMatchPinnedSamples() {
        UnicodeSet union = new UnicodeSet();
        String[] values = PerlUnicodeVerticalOrientationData.canonicalValues();
        for (int i = 0; i < values.length; i++) {
            UnicodeSet current = PerlUnicodeVerticalOrientationData.valueSet(values[i]);
            assertNotNull(current, values[i]);
            assertTrue(current.isFrozen(), values[i]);
            union.addAll(current);
            for (int j = i + 1; j < values.length; j++) {
                assertTrue(new UnicodeSet(current).retainAll(
                        PerlUnicodeVerticalOrientationData.valueSet(values[j])).isEmpty(),
                        values[i] + " overlaps " + values[j]);
            }
        }
        assertEquals(0x110000, union.size());
        assertTrue(union.contains(0, 0x10FFFF));
        assertTrue(PerlUnicodeVerticalOrientationData.valueSet("R").contains(0x0000));
        assertTrue(PerlUnicodeVerticalOrientationData.valueSet("Tr").contains(0x2329));
        assertTrue(PerlUnicodeVerticalOrientationData.valueSet("Tu").contains(0x3001));
        assertTrue(PerlUnicodeVerticalOrientationData.valueSet("U").contains(0x00A7));
    }

    @Test
    void exactJoniPropertyUsesGeneratedDataAndRetainsPerlDefaultDomain() {
        CharacterPropertyResolver.Result upright = UnicodeResolver.resolveJoniProperty(
                "Vertical_Orientation=Upright", false, true);
        CharacterPropertyResolver.Result rotated = UnicodeResolver.resolveJoniProperty(
                "vo=R", false, true);
        assertNotNull(upright);
        assertNotNull(rotated);
        assertFalse(upright.caseFold);
        assertFalse(rotated.caseFold);
        assertTrue(contains(upright.ranges, 0x00A7));
        assertTrue(contains(rotated.ranges, 0x0000));
        assertArrayEquals(new long[] {1, 0x110000L, Long.MAX_VALUE}, rotated.wideRanges);
    }

    private static boolean contains(int[] ranges, int codePoint) {
        if (ranges == null) return false;
        for (int i = 1; i + 1 < ranges.length; i += 2) {
            if (ranges[i] <= codePoint && codePoint <= ranges[i + 1]) return true;
        }
        return false;
    }

    private static UnicodeSet toUnicodeSet(int[] ranges) {
        UnicodeSet set = new UnicodeSet();
        if (ranges != null) {
            for (int i = 1; i + 1 < ranges.length; i += 2) {
                set.add(ranges[i], ranges[i + 1]);
            }
        }
        return set.freeze();
    }
}
