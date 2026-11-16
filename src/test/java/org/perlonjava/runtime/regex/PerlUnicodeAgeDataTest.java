package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.icu.text.UnicodeSet;
import org.joni.CharacterPropertyResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeAgeDataTest {
    @Test
    void usesPinnedPerl545Unicode18Data() {
        assertEquals("18.0.0", PerlUnicodeAgeData.UNICODE_VERSION);
        assertEquals("06c4c8eaf7b0bf34abe73b113da1215bd784ac254d4c223600b90267caa4bbbd",
                PerlUnicodeAgeData.PROP_VALUE_ALIASES_SHA256);
        assertTrue(PerlUnicodeAgeData.exactSet("17.0").contains(0x33479));
        assertFalse(PerlUnicodeAgeData.exactSet("17.0").contains(0x3347a));
        assertTrue(PerlUnicodeAgeData.cumulativeSet("17.0").contains('A'));
        assertTrue(PerlUnicodeAgeData.unassignedSet().contains(0x3347a));
    }

    @Test
    void acceptsGeneratedPerlValueAliases() {
        assertEquals("15.1", PerlUnicodeAgeData.canonicalVersion("V15_1"));
        assertEquals("15.1", PerlUnicodeAgeData.canonicalVersion("15.1"));
        assertEquals(PerlUnicodeAgeData.exactSet("15.1"),
                PerlUnicodeAgeData.exactSet("V15_1"));
        assertTrue(PerlUnicodeAgeData.unassignedAliases().length >= 2);
    }

    @Test
    void everyGeneratedValueAliasWorksAsAnAnchoredWildcard() {
        for (int index = 0; index < PerlUnicodeAgeData.valueCount(); index++) {
            String version = PerlUnicodeAgeData.versionAt(index);
            UnicodeSet expected = PerlUnicodeAgeData.exactSet(version);
            String[] aliases = PerlUnicodeAgeData.wildcardAliases(index);
            assertTrue(aliases.length >= 2, version);
            for (String alias : aliases) {
                CharacterPropertyResolver.Result result =
                        UnicodeResolver.resolveJoniProperty(
                                "Age=:\\A" + alias + "\\z:", false);
                assertNotNull(result, "Age wildcard " + alias);
                assertEquals(expected, toUnicodeSet(result.ranges), alias);
            }
        }
        for (String alias : PerlUnicodeAgeData.unassignedAliases()) {
            CharacterPropertyResolver.Result result =
                    UnicodeResolver.resolveJoniProperty(
                            "Age=:\\A" + alias + "\\z:", false);
            assertNotNull(result, "Age wildcard " + alias);
            assertEquals(PerlUnicodeAgeData.unassignedSet(),
                    toUnicodeSet(result.ranges), alias);
        }
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
