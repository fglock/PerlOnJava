package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlQuickCheckPropertyResolverTest {
    @Test
    void acceptsAssignedFormsAndRejectsBareForms() {
        for (String property : new String[] {
                "NFD_QC", "NFD_Quick_Check", "NFKD_QC", "NFKD_Quick_Check"
        }) {
            assertNotNull(UnicodeResolver.resolveJoniProperty(property + "=No", false),
                    property + " assigned");
            assertThrows(IllegalArgumentException.class,
                    () -> UnicodeResolver.resolveJoniProperty(property, false),
                    property + " bare");
        }
    }
}
