package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class RegexDiagnosticFormatterTest {
    @Test
    void rendersMiddleAndEndMarkersLikePerl() {
        assertEquals(
                "bad token in regex; marked by <-- HERE in m/ab <-- HERE cd/",
                RegexDiagnosticFormatter.marked("abcd", 2, "bad token"));
        assertEquals(
                "bad token in regex; marked by <-- HERE in m/abcd <-- HERE/",
                RegexDiagnosticFormatter.marked("abcd", 4, "bad token"));
    }

    @Test
    void clampsEnginePositionsAtPatternBoundaries() {
        assertEquals(
                "bad token in regex; marked by <-- HERE in m/ <-- HERE abcd/",
                RegexDiagnosticFormatter.marked("abcd", -1, "bad token"));
        assertEquals(
                "bad token in regex; marked by <-- HERE in m/abcd <-- HERE/",
                RegexDiagnosticFormatter.marked("abcd", 99, "bad token"));
    }
}
