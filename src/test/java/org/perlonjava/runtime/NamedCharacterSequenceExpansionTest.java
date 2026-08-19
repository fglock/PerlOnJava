package org.perlonjava.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class NamedCharacterSequenceExpansionTest {

    @Test
    void resolvesPinnedSequencesBeforeOrdinaryScalarNames() {
        NamedCharacterExpansion sequence = NamedCharacterExpansion.resolve(
                "KEYCAP DIGIT NINE", null,
                NamedCharacterExpansion.SourceMode.BYTE);
        assertTrue(sequence.resolved());
        assertEquals("9\uFE0F\u20E3", sequence.sequence());
        assertEquals(NamedCharacterExpansion.SourceMode.UNICODE,
                sequence.sourceMode());
        assertTrue(sequence.promotesUnicode());

        NamedCharacterExpansion scalar = NamedCharacterExpansion.resolve(
                "LATIN CAPITAL LETTER A", null,
                NamedCharacterExpansion.SourceMode.BYTE);
        assertTrue(scalar.resolved());
        assertEquals("A", scalar.sequence());
    }
}
