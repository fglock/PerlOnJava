package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeCombiningClassDataTest {
    @Test
    void usesPinnedPerl544Unicode17DataAndAliases() {
        assertEquals("17.0.0", PerlUnicodeCombiningClassData.UNICODE_VERSION);
        assertTrue(PerlUnicodeCombiningClassData.resolve("A").contains(0x301));
        assertTrue(PerlUnicodeCombiningClassData.resolve("+00_230").contains(0x301));
        assertTrue(PerlUnicodeCombiningClassData.resolve(":\\AAbove\\z:").contains(0x301));
        assertTrue(PerlUnicodeCombiningClassData.resolve("Overlay").contains(0x334));
        assertTrue(PerlUnicodeCombiningClassData.resolve("10").contains(0x5b0));
        assertTrue(PerlUnicodeCombiningClassData.resolve("NR").contains('A'));
        assertFalse(PerlUnicodeCombiningClassData.resolve("230").contains('A'));
        assertTrue(PerlUnicodeCombiningClassData.resolve("133").isEmpty());
        assertNull(PerlUnicodeCombiningClassData.resolve("not_a_class"));
    }
}
