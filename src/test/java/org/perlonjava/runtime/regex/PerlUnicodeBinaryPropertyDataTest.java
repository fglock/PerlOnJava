package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ibm.icu.text.UnicodeSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeBinaryPropertyDataTest {
    @Test
    void usesPinnedPerl544Unicode17Data() {
        assertEquals("17.0.0", PerlUnicodeBinaryPropertyData.UNICODE_VERSION);
        assertEquals(51, PerlUnicodeBinaryPropertyData.propertyCount());
        assertEquals(98, PerlUnicodeBinaryPropertyData.aliasCount());
        assertEquals(11_823, PerlUnicodeBinaryPropertyData.totalRangeCount());

        int cardinality = 0;
        for (int propertyId = 0;
                propertyId < PerlUnicodeBinaryPropertyData.propertyCount(); propertyId++) {
            UnicodeSet set = PerlUnicodeBinaryPropertyData.set(propertyId);
            assertTrue(set.isFrozen(), PerlUnicodeBinaryPropertyData.canonicalProperty(propertyId));
            assertEquals(propertyId, PerlUnicodeBinaryPropertyData.property(
                    PerlUnicodeBinaryPropertyData.canonicalProperty(propertyId)));
            assertEquals(PerlUnicodeBinaryPropertyData.rangeCount(propertyId), set.getRangeCount());
            assertEquals(PerlUnicodeBinaryPropertyData.cardinality(propertyId), set.size());
            cardinality += set.size();
        }
        assertEquals(1_158_810, cardinality);
    }

    @Test
    void preservesOverlapsAndContainmentsBetweenProperties() {
        int overlapping = 0;
        int disjoint = 0;
        int equal = 0;
        int strictContainments = 0;
        for (int left = 0; left < PerlUnicodeBinaryPropertyData.propertyCount(); left++) {
            UnicodeSet leftSet = PerlUnicodeBinaryPropertyData.set(left);
            for (int right = left + 1;
                    right < PerlUnicodeBinaryPropertyData.propertyCount(); right++) {
                UnicodeSet rightSet = PerlUnicodeBinaryPropertyData.set(right);
                boolean leftContainsRight = leftSet.containsAll(rightSet);
                boolean rightContainsLeft = rightSet.containsAll(leftSet);
                if (leftSet.containsSome(rightSet)) overlapping++;
                else disjoint++;
                if (leftContainsRight && rightContainsLeft) equal++;
                else if (leftContainsRight || rightContainsLeft) strictContainments++;
            }
        }
        assertEquals(400, overlapping);
        assertEquals(875, disjoint);
        assertEquals(0, equal);
        assertEquals(95, strictContainments);
    }

    @Test
    void resolvesPerlLooseAliasesAndBooleanValues() {
        assertEquals(
                PerlUnicodeBinaryPropertyData.property("White_Space"),
                PerlUnicodeBinaryPropertyData.property("w-h i\tt_e s p a c e"));
        assertEquals(
                PerlUnicodeBinaryPropertyData.property("WSpace"),
                PerlUnicodeBinaryPropertyData.property("space"));
        assertEquals(
                PerlUnicodeBinaryPropertyData.property("Composition_Exclusion"),
                PerlUnicodeBinaryPropertyData.property("CE"));
        assertEquals(
                PerlUnicodeBinaryPropertyData.property("Emoji_Modifier_Base"),
                PerlUnicodeBinaryPropertyData.property("EBase"));
        assertEquals(
                PerlUnicodeBinaryPropertyData.property("Uppercase"),
                PerlUnicodeBinaryPropertyData.assignmentProperty("Is_Uppercase"));
        assertEquals(
                PerlUnicodeBinaryPropertyData.property("Uppercase"),
                PerlUnicodeBinaryPropertyData.assignmentProperty("IsUppercase"));
        assertEquals(PerlUnicodeBinaryPropertyData.INVALID,
                PerlUnicodeBinaryPropertyData.assignmentProperty("isUppercase"));
        assertEquals(PerlUnicodeBinaryPropertyData.INVALID,
                PerlUnicodeBinaryPropertyData.property("ASCII_Hex_Digit"));
        assertEquals(PerlUnicodeBinaryPropertyData.INVALID,
                PerlUnicodeBinaryPropertyData.property("\u0130D_Start"));
        assertNull(PerlUnicodeBinaryPropertyData.set("Hyphen"));

        for (String yes : new String[] {"Y", "Yes", "T", "True"}) {
            assertEquals(PerlUnicodeBinaryPropertyData.TRUE,
                    PerlUnicodeBinaryPropertyData.booleanValue(yes));
        }
        for (String no : new String[] {"N", "No", "F", "False"}) {
            assertEquals(PerlUnicodeBinaryPropertyData.FALSE,
                    PerlUnicodeBinaryPropertyData.booleanValue(no));
        }
        assertEquals(PerlUnicodeBinaryPropertyData.INVALID,
                PerlUnicodeBinaryPropertyData.booleanValue("1"));
        assertEquals(PerlUnicodeBinaryPropertyData.INVALID,
                PerlUnicodeBinaryPropertyData.booleanValue("0"));
    }

    @Test
    void includesUnicode17SentinelsAndPropertyDistinctions() {
        assertContains("Alphabetic", 0x088f);
        assertContains("ID_Start", 0x088f);
        assertContains("Uppercase", 0xa7ce);
        assertContains("Lowercase", 0xa7cf);
        assertContains("Changes_When_NFKC_Casefolded", 0xa7ce);
        assertContains("Grapheme_Extend", 0x1acf);
        assertContains("Extender", 0x11dd9);
        assertContains("Ideographic", 0x16ff2);
        assertContains("Math", 0x1cef0);
        assertContains("Pattern_Syntax", 0x2b96);
        assertContains("Emoji_Presentation", 0x1f6d8);
        assertContains("Unified_Ideograph", 0x2b73a);

        assertContains("Composition_Exclusion", 0x0958);
        assertContains("Full_Composition_Exclusion", 0x0958);
        assertContains("Full_Composition_Exclusion", 0x0340);
        assertFalse(PerlUnicodeBinaryPropertyData.set("Composition_Exclusion").contains(0x0340));
        assertContains("Emoji_Modifier", 0x1f3fb);
        assertContains("Emoji_Component", 0x1f3fb);
        assertContains("Emoji_Modifier_Base", 0x261d);
        assertNotEquals(
                PerlUnicodeBinaryPropertyData.set("Ideographic"),
                PerlUnicodeBinaryPropertyData.set("Unified_Ideograph"));
    }

    @Test
    void generatorReproducesCheckedInDataByteForByte() throws IOException, InterruptedException {
        Path root = Path.of("").toAbsolutePath();
        Path generator = root.resolve("dev/tools/generate_perl_unicode_binary_property_data.pl");
        assumeTrue(Files.isRegularFile(root.resolve("perl5/lib/unicore/version")),
                "pinned Perl Unicode sources are unavailable in this checkout");
        Path generated = root.resolve(
                "src/main/java/org/perlonjava/runtime/regex/PerlUnicodeBinaryPropertyData.java");
        Process process = new ProcessBuilder("perl", generator.toString())
                .directory(root.toFile())
                .start();
        byte[] actual = process.getInputStream().readAllBytes();
        byte[] errors = process.getErrorStream().readAllBytes();
        assertEquals(0, process.waitFor(), new String(errors));
        assertArrayEquals(Files.readAllBytes(generated), actual);
    }

    private static void assertContains(String property, int codePoint) {
        assertTrue(PerlUnicodeBinaryPropertyData.set(property).contains(codePoint),
                property + " should contain U+" + Integer.toHexString(codePoint).toUpperCase());
    }
}
