package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class RegexDiagnosticPerlMarkerTest {
    @Test
    void preservesPerlWhitespaceAroundMiddleAndEndMarkers() {
        assertEquals(
                "bad token in regex; marked by <-- HERE in m/ab <-- HERE cd/",
                RegexDiagnosticFormatter.markedPerl("abcd", 2, "bad token"));
        assertEquals(
                "bad token in regex; marked by <-- HERE in m/abcd <-- HERE /",
                RegexDiagnosticFormatter.markedPerl("abcd", 4, "bad token"));
    }
}
