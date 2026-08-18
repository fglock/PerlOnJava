package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeNumericValueDataTest {
    private static final int MAX_CODE_POINT = 0x10ffff;

    @Test
    void usesPinnedPerl544Unicode17Data() {
        assertEquals("17.0.0", PerlUnicodeNumericValueData.UNICODE_VERSION);
        assertEquals(144, PerlUnicodeNumericValueData.valueCount());

        assertTrue(set("-1/2").contains(0x0f33));
        assertTrue(set("1/2").contains(0x00bd));
        assertTrue(set("0").contains('0'));
        assertTrue(set("10000000000000000").contains(0x4eac));
        assertTrue(set("3/2").contains(0x16ff5));
        assertFalse(set("3/2").contains(0x16ff6));
        assertTrue(set("2").contains(0x16ff6));
        assertTrue(PerlUnicodeNumericValueData.nanSet().contains('A'));
        assertTrue(PerlUnicodeNumericValueData.nanSet().contains(MAX_CODE_POINT));
    }

    @Test
    void formsACompleteDisjointPartition() {
        var union = new com.ibm.icu.text.UnicodeSet();
        int rangeCount = 0;
        for (int index = 0; index < PerlUnicodeNumericValueData.valueCount(); index++) {
            var set = PerlUnicodeNumericValueData.set(index);
            assertTrue(set.isFrozen(), PerlUnicodeNumericValueData.canonicalValue(index));
            assertFalse(set.isEmpty(), PerlUnicodeNumericValueData.canonicalValue(index));
            assertTrue(new com.ibm.icu.text.UnicodeSet(union).retainAll(set).isEmpty(),
                    PerlUnicodeNumericValueData.canonicalValue(index));
            union.addAll(set);
            rangeCount += set.getRangeCount();
        }

        assertEquals(1_979, rangeCount);
        assertEquals(2_023, union.size());
        assertEquals(PerlUnicodeNumericValueData.assignedSet(), union);
        assertTrue(PerlUnicodeNumericValueData.assignedSet().isFrozen());
        assertTrue(PerlUnicodeNumericValueData.nanSet().isFrozen());
        assertTrue(new com.ibm.icu.text.UnicodeSet(union)
                .retainAll(PerlUnicodeNumericValueData.nanSet()).isEmpty());
        assertEquals(1_112_089, PerlUnicodeNumericValueData.nanSet().size());
        union.addAll(PerlUnicodeNumericValueData.nanSet());
        assertEquals(new com.ibm.icu.text.UnicodeSet(0, MAX_CODE_POINT), union);
    }

    @Test
    void exposesReducedExactRationals() {
        for (short index = 0; index < PerlUnicodeNumericValueData.valueCount(); index++) {
            long numerator = PerlUnicodeNumericValueData.numerator(index);
            int denominator = PerlUnicodeNumericValueData.denominator(index);
            String expected = denominator == 1
                    ? Long.toString(numerator)
                    : numerator + "/" + denominator;
            assertEquals(expected, PerlUnicodeNumericValueData.canonicalValue(index));
            assertEquals(index,
                    PerlUnicodeNumericValueData.valueForRational(numerator, denominator));
        }

        assertEquals(index("1/2"), PerlUnicodeNumericValueData.valueForRational(2, 4));
        assertEquals(index("-1/2"), PerlUnicodeNumericValueData.valueForRational(1, -2));
        assertEquals(index("1/2"), PerlUnicodeNumericValueData.valueForRational(-1, -2));
        assertEquals(PerlUnicodeNumericValueData.INVALID,
                PerlUnicodeNumericValueData.valueForRational(1, 0));
        assertEquals(PerlUnicodeNumericValueData.INVALID,
                PerlUnicodeNumericValueData.valueForRational(7, 11));
        assertEquals(PerlUnicodeNumericValueData.INVALID,
                PerlUnicodeNumericValueData.valueForRational(Long.MIN_VALUE, 1));
    }

    @Test
    void exposesPinnedPerlDecimalKeywordAliases() {
        assertEquals(index("1/12"),
                PerlUnicodeNumericValueData.valueForDecimal(new BigDecimal("8.333e-02")));
        assertEquals(index("1/64"),
                PerlUnicodeNumericValueData.valueForDecimal(new BigDecimal("0.01562")));
        assertEquals(index("1/6"),
                PerlUnicodeNumericValueData.valueForDecimal(new BigDecimal("0.1667")));
        assertEquals(index("3/64"),
                PerlUnicodeNumericValueData.valueForDecimal(new BigDecimal("4.688e-02")));
        assertEquals(PerlUnicodeNumericValueData.INVALID,
                PerlUnicodeNumericValueData.valueForDecimal(new BigDecimal("0.1668")));
    }

    @Test
    void followsPerlLoosePropertyAliasRules() {
        assertTrue(PerlUnicodeNumericValueData.isPropertyAlias("nv"));
        assertTrue(PerlUnicodeNumericValueData.isPropertyAlias("NV"));
        assertTrue(PerlUnicodeNumericValueData.isPropertyAlias("Numeric_Value"));
        assertTrue(PerlUnicodeNumericValueData.isPropertyAlias("numeric-value"));
        assertTrue(PerlUnicodeNumericValueData.isPropertyAlias("numeric value"));
        assertTrue(PerlUnicodeNumericValueData.isPropertyAlias("Is_Nv"));
        assertTrue(PerlUnicodeNumericValueData.isPropertyAlias("IsNumericValue"));

        assertFalse(PerlUnicodeNumericValueData.isPropertyAlias("is_Nv"));
        assertFalse(PerlUnicodeNumericValueData.isPropertyAlias("IS_NV"));
        assertFalse(PerlUnicodeNumericValueData.isPropertyAlias("Numeric\u1680Value"));
        assertFalse(PerlUnicodeNumericValueData.isPropertyAlias("value"));
        assertFalse(PerlUnicodeNumericValueData.isPropertyAlias(null));
    }

    @Test
    void generatorReproducesCheckedInDataByteForByte() throws Exception {
        Path generator = Path.of("dev", "tools", "generate_perl_unicode_numeric_value_data.pl");
        Path generated = Path.of("src", "main", "java", "org", "perlonjava", "runtime",
                "regex", "PerlUnicodeNumericValueData.java");
        Process process = new ProcessBuilder("perl", generator.toString())
                .redirectErrorStream(true)
                .start();
        byte[] actual = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "generator timed out");
        assertEquals(0, process.exitValue(), new String(actual, StandardCharsets.UTF_8));
        assertArrayEquals(Files.readAllBytes(generated), actual);
    }

    private static com.ibm.icu.text.UnicodeSet set(String canonicalValue) {
        return PerlUnicodeNumericValueData.set(index(canonicalValue));
    }

    private static short index(String canonicalValue) {
        for (short index = 0; index < PerlUnicodeNumericValueData.valueCount(); index++) {
            if (canonicalValue.equals(PerlUnicodeNumericValueData.canonicalValue(index))) {
                return index;
            }
        }
        throw new AssertionError("Unknown Numeric_Value " + canonicalValue);
    }
}
