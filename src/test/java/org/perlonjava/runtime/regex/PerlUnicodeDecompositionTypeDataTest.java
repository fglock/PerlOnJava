package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeDecompositionTypeDataTest {
    private static final byte[] VALUES = {
        PerlUnicodeDecompositionTypeData.CANONICAL,
        PerlUnicodeDecompositionTypeData.COMPAT,
        PerlUnicodeDecompositionTypeData.CIRCLE,
        PerlUnicodeDecompositionTypeData.FINAL,
        PerlUnicodeDecompositionTypeData.FONT,
        PerlUnicodeDecompositionTypeData.FRACTION,
        PerlUnicodeDecompositionTypeData.INITIAL,
        PerlUnicodeDecompositionTypeData.ISOLATED,
        PerlUnicodeDecompositionTypeData.MEDIAL,
        PerlUnicodeDecompositionTypeData.NARROW,
        PerlUnicodeDecompositionTypeData.NOBREAK,
        PerlUnicodeDecompositionTypeData.NONE,
        PerlUnicodeDecompositionTypeData.SMALL,
        PerlUnicodeDecompositionTypeData.SQUARE,
        PerlUnicodeDecompositionTypeData.SUB,
        PerlUnicodeDecompositionTypeData.SUPER,
        PerlUnicodeDecompositionTypeData.VERTICAL,
        PerlUnicodeDecompositionTypeData.WIDE,
    };

    private static final long[] COUNTS = {
        13_253, 720, 240, 240, 1_230, 20, 171, 238, 82,
        122, 5, 1_097_026, 26, 286, 64, 250, 35, 104,
    };

    private static final String[][] ALIASES = {
        {"Can", "Canonical", "can"},
        {"Com", "Compat", "com"},
        {"Enc", "Circle", "enc"},
        {"Fin", "Final", "fin"},
        {"Font", "Font", "font"},
        {"Fra", "Fraction", "fra"},
        {"Init", "Initial", "init"},
        {"Iso", "Isolated", "iso"},
        {"Med", "Medial", "med"},
        {"Nar", "Narrow", "nar"},
        {"Nb", "Nobreak", "nb"},
        {"None", "None", "none"},
        {"Sml", "Small", "sml"},
        {"Sqr", "Square", "sqr"},
        {"Sub", "Sub", "sub"},
        {"Sup", "Super", "sup"},
        {"Vert", "Vertical", "vert"},
        {"Wide", "Wide", "wide"},
    };

    @Test
    public void usesPinnedUnicode17ValuesAndBoundaries() {
        assertEquals("17.0.0", PerlUnicodeDecompositionTypeData.UNICODE_VERSION);
        assertEquals(PerlUnicodeDecompositionTypeData.NONE, PerlUnicodeDecompositionTypeData.propertyOf(0x0041));
        assertEquals(PerlUnicodeDecompositionTypeData.COMPAT, PerlUnicodeDecompositionTypeData.propertyOf(0x00A8));
        assertEquals(PerlUnicodeDecompositionTypeData.CANONICAL, PerlUnicodeDecompositionTypeData.propertyOf(0x00C0));
        assertEquals(PerlUnicodeDecompositionTypeData.CANONICAL, PerlUnicodeDecompositionTypeData.propertyOf(0xAC00));
        assertEquals(PerlUnicodeDecompositionTypeData.CANONICAL, PerlUnicodeDecompositionTypeData.propertyOf(0xD7A3));
        assertEquals(PerlUnicodeDecompositionTypeData.NONE, PerlUnicodeDecompositionTypeData.propertyOf(0xD7A4));
        assertEquals(PerlUnicodeDecompositionTypeData.NONE, PerlUnicodeDecompositionTypeData.propertyOf(0xA7F0));
        assertEquals(PerlUnicodeDecompositionTypeData.SUPER, PerlUnicodeDecompositionTypeData.propertyOf(0xA7F1));
        assertEquals(PerlUnicodeDecompositionTypeData.SUPER, PerlUnicodeDecompositionTypeData.propertyOf(0xA7F4));
        assertEquals(PerlUnicodeDecompositionTypeData.NONE, PerlUnicodeDecompositionTypeData.propertyOf(0xA7F5));
        assertEquals(PerlUnicodeDecompositionTypeData.NONE, PerlUnicodeDecompositionTypeData.propertyOf(0x10FFFF));
        assertThrows(IllegalArgumentException.class, () -> PerlUnicodeDecompositionTypeData.propertyOf(-1));
        assertThrows(IllegalArgumentException.class, () -> PerlUnicodeDecompositionTypeData.propertyOf(0x110000));
    }

    @Test
    public void completelyPartitionsEveryUnicodeCodePoint() {
        assertEquals(1_212, PerlUnicodeDecompositionTypeData.rangeCount());
        assertEquals(0, PerlUnicodeDecompositionTypeData.rangeStart(0));
        assertEquals(0x10FFFF, PerlUnicodeDecompositionTypeData.rangeEnd(
                PerlUnicodeDecompositionTypeData.rangeCount() - 1));
        for (int index = 0; index < PerlUnicodeDecompositionTypeData.rangeCount(); index++) {
            assertEquals(PerlUnicodeDecompositionTypeData.rangeValue(index),
                    PerlUnicodeDecompositionTypeData.propertyOf(
                            PerlUnicodeDecompositionTypeData.rangeStart(index)));
            assertEquals(PerlUnicodeDecompositionTypeData.rangeValue(index),
                    PerlUnicodeDecompositionTypeData.propertyOf(
                            PerlUnicodeDecompositionTypeData.rangeEnd(index)));
            if (index > 0) {
                assertEquals(PerlUnicodeDecompositionTypeData.rangeEnd(index - 1) + 1,
                        PerlUnicodeDecompositionTypeData.rangeStart(index));
                assertTrue(PerlUnicodeDecompositionTypeData.rangeValue(index - 1)
                        != PerlUnicodeDecompositionTypeData.rangeValue(index));
            }
        }

        long[] actualCounts = new long[VALUES.length];
        int transitions = 0;
        byte previous = PerlUnicodeDecompositionTypeData.INVALID;
        long nonCanonical = 0;
        for (int codePoint = 0; codePoint <= 0x10FFFF; codePoint++) {
            byte value = PerlUnicodeDecompositionTypeData.propertyOf(codePoint);
            if (value < 0 || value >= VALUES.length) {
                fail("invalid value at U+" + Integer.toHexString(codePoint));
            }
            actualCounts[value]++;
            if (value != previous) {
                transitions++;
                previous = value;
            }
            if (PerlUnicodeDecompositionTypeData.matches(value, PerlUnicodeDecompositionTypeData.NON_CANONICAL)) {
                nonCanonical++;
            }
        }
        assertArrayEquals(COUNTS, actualCounts);
        assertEquals(1_212, transitions);
        assertEquals(3_833, nonCanonical);
    }

    @Test
    public void acceptsAllLooseAliasesIncludingPerlUnionValues() {
        assertTrue(PerlUnicodeDecompositionTypeData.isPropertyAlias("dt"));
        assertTrue(PerlUnicodeDecompositionTypeData.isPropertyAlias("Decomposition Type"));
        assertTrue(PerlUnicodeDecompositionTypeData.isPropertyAlias("Is-d_t"));
        assertTrue(PerlUnicodeDecompositionTypeData.isPropertyAlias("Is Decomposition_Type"));
        assertTrue(PerlUnicodeDecompositionTypeData.isPropertyAlias("Decomposition\tType"));
        assertFalse(PerlUnicodeDecompositionTypeData.isPropertyAlias("is-d_t"));
        assertFalse(PerlUnicodeDecompositionTypeData.isPropertyAlias("IS_DT"));
        assertFalse(PerlUnicodeDecompositionTypeData.isPropertyAlias("Decomposition\u1680Type"));
        assertFalse(PerlUnicodeDecompositionTypeData.isPropertyAlias("decomposition mapping"));
        assertFalse(PerlUnicodeDecompositionTypeData.isPropertyAlias(null));

        for (int id = 0; id < ALIASES.length; id++) {
            for (String alias : ALIASES[id]) {
                assertEquals(VALUES[id],
                        PerlUnicodeDecompositionTypeData.valueForAlias(alias), alias);
            }
            assertEquals(ALIASES[id][1], PerlUnicodeDecompositionTypeData.canonicalValueName(VALUES[id]));
        }
        assertEquals(PerlUnicodeDecompositionTypeData.CANONICAL,
                PerlUnicodeDecompositionTypeData.valueForAlias("c_a-n"));
        assertEquals(PerlUnicodeDecompositionTypeData.INVALID,
                PerlUnicodeDecompositionTypeData.valueForAlias("c\u1680an"));
        assertEquals(PerlUnicodeDecompositionTypeData.NON_CANONICAL,
                PerlUnicodeDecompositionTypeData.valueForAlias("Non Canon"));
        assertEquals(PerlUnicodeDecompositionTypeData.NON_CANONICAL,
                PerlUnicodeDecompositionTypeData.valueForAlias("non-canonical"));
        assertEquals("Non_Canonical",
                PerlUnicodeDecompositionTypeData.canonicalValueName(PerlUnicodeDecompositionTypeData.NON_CANONICAL));
        assertEquals(PerlUnicodeDecompositionTypeData.INVALID,
                PerlUnicodeDecompositionTypeData.valueForAlias("reserved"));
        assertEquals(PerlUnicodeDecompositionTypeData.INVALID,
                PerlUnicodeDecompositionTypeData.valueForAlias(null));
        assertTrue(PerlUnicodeDecompositionTypeData.matches(
                PerlUnicodeDecompositionTypeData.COMPAT, PerlUnicodeDecompositionTypeData.NON_CANONICAL));
        assertFalse(PerlUnicodeDecompositionTypeData.matches(
                PerlUnicodeDecompositionTypeData.CANONICAL, PerlUnicodeDecompositionTypeData.NON_CANONICAL));
        assertFalse(PerlUnicodeDecompositionTypeData.matches(
                PerlUnicodeDecompositionTypeData.NONE, PerlUnicodeDecompositionTypeData.NON_CANONICAL));
    }

    @Test
    public void generatorReproducesTheCheckedInClassByteForByte() throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        Path generator = root.resolve("dev/tools/generate_perl_unicode_decomposition_type_data.pl");
        Path generated = root.resolve("src/main/java/org/perlonjava/runtime/regex/PerlUnicodeDecompositionTypeData.java");

        Process process = new ProcessBuilder("perl", generator.toString())
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        assertEquals(0, process.waitFor(), new String(output.toByteArray()));
        assertArrayEquals(canonicalLf(Files.readAllBytes(generated)),
                canonicalLf(output.toByteArray()));
    }

    private static byte[] canonicalLf(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .getBytes(StandardCharsets.UTF_8);
    }
}
