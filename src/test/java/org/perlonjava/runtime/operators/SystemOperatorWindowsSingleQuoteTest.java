package org.perlonjava.runtime.operators;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class SystemOperatorWindowsSingleQuoteTest {

    @Test
    void convertsPortableSingleQuotedProgramBeforeCmdExpansion() {
        assertEquals(
                "jperl -e \"qr/((?+2147483647))/\" 2>&1",
                SystemOperator.normalizeWindowsShellSingleQuotes(
                        "jperl -e 'qr/((?+2147483647))/' 2>&1"));
    }

    @Test
    void leavesApostrophesInsideDoubleQuotesAndUnmatchedQuotesAlone() {
        assertEquals(
                "jperl -e \"print q(don't)\"",
                SystemOperator.normalizeWindowsShellSingleQuotes(
                        "jperl -e \"print q(don't)\""));
        assertEquals(
                "jperl -e 'unterminated",
                SystemOperator.normalizeWindowsShellSingleQuotes(
                        "jperl -e 'unterminated"));
    }
}
