package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlXidPropertyResolverTest {
    private static final RegexFlags FLAGS = RegexFlags.fromModifiers("", "XID");

    @Test
    void usesTheGeneratedCurrentPerlAliasInventory() {
        assertEquals("XID_Continue",
                PerlUnicodeBinaryPropertyAliasData.canonicalProperty("XIDC"));
        assertEquals("XID_Continue",
                PerlUnicodeBinaryPropertyAliasData.canonicalProperty("IsXID Continue"));
        assertTrue(PerlUnicodeBinaryPropertyAliasData.isRejected("XIDCont"));
        assertTrue(PerlUnicodeBinaryPropertyAliasData.isRejected("Gr_Link"));
        assertFalse(PerlUnicodeBinaryPropertyAliasData.isRejected("XIDC"));
    }

    @Test
    void resolvesEveryPerlXidAliasNativelyThroughJoni() {
        for (String alias : new String[] {
                "XIDS", "XIDStart", "XID_Start", "xid start", "IsXID_Start"
        }) {
            JoniRegexPattern pattern = new JoniRegexPattern("\\p{" + alias + "}", FLAGS);
            assertEquals("\\p{" + alias + "}", pattern.patternDescription(), alias);
            assertTrue(matches(pattern, "A"), alias);
            assertTrue(matches(pattern, "\u2118"), alias);
            assertFalse(matches(pattern, "0"), alias);
            assertFalse(matches(pattern, "_"), alias);
        }

        for (String alias : new String[] {
                "XIDC", "XID_Continue", "xid continue", "IsXID_Continue"
        }) {
            JoniRegexPattern pattern = new JoniRegexPattern("\\p{" + alias + "}", FLAGS);
            assertEquals("\\p{" + alias + "}", pattern.patternDescription(), alias);
            assertTrue(matches(pattern, "A"), alias);
            assertTrue(matches(pattern, "0"), alias);
            assertTrue(matches(pattern, "_"), alias);
            assertTrue(matches(pattern, "\u0301"), alias);
            assertTrue(matches(pattern, "\u200C"), alias);
            assertFalse(matches(pattern, "\uD83D\uDE00"), alias);
        }
    }

    @Test
    void composesXidBooleanAssignmentsAndComplements() {
        assertTrue(matches("\\p{XID_Start=Yes}", "A"));
        assertFalse(matches("\\p{XID_Start=Yes}", "0"));
        assertFalse(matches("\\p{XID_Start=No}", "A"));
        assertTrue(matches("\\p{XID_Start=No}", "0"));
        assertTrue(matches("\\p{XID_Continue=Yes}", "0"));
        assertFalse(matches("\\p{XID_Continue=No}", "0"));
        assertTrue(matches("\\p{XID_Continue=No}", "\uD83D\uDE00"));
        assertFalse(matches("\\p{^XID_Continue}", "0"));
        assertTrue(matches("\\P{^XID_Continue}", "0"));
    }

    @Test
    void binaryAssignmentsPublishAnAuthoritativeWideDomain() {
        assertArrayEquals(new long[] {0},
                UnicodeResolver.resolveJoniProperty(
                        "ASCII_Hex_Digit=True", false).wideRanges);
        assertArrayEquals(new long[] {1, 0x110000L, Long.MAX_VALUE},
                UnicodeResolver.resolveJoniProperty(
                        "ASCII_Hex_Digit=False", false).wideRanges);
    }

    @Test
    void rejectsTheNonPerlXidContAbbreviation() {
        for (String alias : new String[] {
                "XIDCont", "XIDCont=Yes", "Gr_Link", "Gr_Link=Y"
        }) {
            assertThrows(RuntimeException.class,
                    () -> new JoniRegexPattern("\\p{" + alias + "}", FLAGS), alias);
        }
    }

    private static boolean matches(String pattern, String subject) {
        return matches(new JoniRegexPattern(pattern, FLAGS), subject);
    }

    private static boolean matches(JoniRegexPattern pattern, String subject) {
        return pattern.matcher(subject, List.of()).find();
    }
}
