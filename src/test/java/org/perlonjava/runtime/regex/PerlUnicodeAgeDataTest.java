package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeAgeDataTest {
    @Test
    void usesPinnedPerl544Unicode17Data() {
        assertEquals("17.0.0", PerlUnicodeAgeData.UNICODE_VERSION);
        assertTrue(PerlUnicodeAgeData.exactSet("17.0").contains(0x33479));
        assertFalse(PerlUnicodeAgeData.exactSet("17.0").contains(0x3347a));
        assertTrue(PerlUnicodeAgeData.cumulativeSet("17.0").contains('A'));
        assertTrue(PerlUnicodeAgeData.unassignedSet().contains(0x3347a));
    }
}
