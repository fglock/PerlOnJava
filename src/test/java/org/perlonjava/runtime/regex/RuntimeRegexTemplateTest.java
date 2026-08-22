package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class RuntimeRegexTemplateTest {
    @Test
    void rejectsMalformedInternalCalloutSlots() {
        String[] malformed = {
                "\u001e",
                "\u001eX0\u001f",
                "\u001eB\u001f",
                "\u001eB0",
                "\u001eB2147483648\u001f",
        };

        for (String pattern : malformed) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> RuntimeRegexTemplate.materializeTrustedCallouts(pattern, 1));
            assertEquals("Malformed internal regex callout slot", error.getMessage());
        }
    }

    @Test
    void preservesRawSlotBytesOutsideTheStructuralTemplateBoundary() {
        String raw = "\u001eB0\u001f";
        assertEquals(raw, RuntimeRegexTemplate.materializeTrustedCallouts(raw, 0));
        assertEquals(raw, RuntimeRegexTemplate.materializeTrustedCallouts(
                "\u001e\u001eB0\u001f", 1));
    }

    @Test
    void materializesOptimisticCalloutIdentityForJoni() {
        assertEquals("(?{=OPTIMISTIC:0})",
                RuntimeRegexTemplate.materializeTrustedCallouts(
                        "\u001eO0\u001f", 1));
        assertEquals("?{=OPTIMISTIC:0})",
                RuntimeRegexTemplate.materializeTrustedCallouts(
                        "\u001eP0\u001f", 1));
    }
}
