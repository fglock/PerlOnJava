package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.joni.Regex.ParsedProgramFeature.NATIVE_EXTENDED_CLASS_LEAF;
import static org.perlonjava.runtime.regex.JoniProgramFacts.has;

@Tag("unit")
class NativeExtendedClassRoutingTest {
    @Test
    void recordsRealExtendedClassesInJoni() {
        assertTrue(has("(?[ [:ascii:] & [:graph:] ])", NATIVE_EXTENDED_CLASS_LEAF));
    }

    @Test
    void ignoresEscapedAndQuotedExtendedClassLookalikes() {
        assertFalse(has("\\(\\?\\[", NATIVE_EXTENDED_CLASS_LEAF));
        assertFalse(has("\\Q(?[ [:ascii:] ])\\E", NATIVE_EXTENDED_CLASS_LEAF));
    }
}
