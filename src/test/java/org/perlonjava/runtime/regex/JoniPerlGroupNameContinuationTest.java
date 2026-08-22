package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joni.exception.SyntaxException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;

@Tag("unit")
class JoniPerlGroupNameContinuationTest {
    @Test
    void rejectsAWordCharacterOutsidePerlXidContinue() {
        assertFalse(UnicodeResolver.isXIDContinue(0x24b7));
        SyntaxException error = assertThrows(SyntaxException.class,
                () -> new JoniRegexPattern("(?<a\u24b7b>abc)",
                        RegexFlags.fromModifiers("", "capture name")));
        assertTrue(error.getMessage().contains(
                "\\x{24B7} is a \\w char that isn't valid in a name"));
    }

    @Test
    void hostCompileBoundaryRetainsThePerlDiagnostic() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> RuntimeRegex.compile("(?<a\u24b7b>abc)", ""));
            assertTrue(error.getMessage().contains(
                    "\\x{24B7} is a \\w char that isn't valid in a name"));
            assertTrue(error.getMessage().contains("marked by <-- HERE after"));
        }
    }
}
