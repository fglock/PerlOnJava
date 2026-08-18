package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.icu.text.UnicodeSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeIndicCategoryDataTest {
    private static final String[] INPC_VALUES = {
        "Bottom", "Bottom_And_Left", "Bottom_And_Right", "Left",
        "Left_And_Right", "Not_Applicable", "Overstruck", "Right", "Top",
        "Top_And_Bottom", "Top_And_Bottom_And_Left", "Top_And_Bottom_And_Right",
        "Top_And_Left", "Top_And_Left_And_Right", "Top_And_Right",
        "Visual_Order_Left"
    };

    private static final String[] INSC_VALUES = {
        "Avagraha", "Bindu", "Brahmi_Joining_Number", "Cantillation_Mark",
        "Consonant", "Consonant_Dead", "Consonant_Final", "Consonant_Head_Letter",
        "Consonant_Initial_Postfixed", "Consonant_Killer", "Consonant_Medial",
        "Consonant_Placeholder", "Consonant_Preceding_Repha", "Consonant_Prefixed",
        "Consonant_Subjoined", "Consonant_Succeeding_Repha", "Consonant_With_Stacker",
        "Gemination_Mark", "Invisible_Stacker", "Joiner", "Modifying_Letter",
        "Non_Joiner", "Nukta", "Number", "Number_Joiner", "Other", "Pure_Killer",
        "Register_Shifter", "Reordering_Killer", "Syllable_Modifier", "Tone_Letter",
        "Tone_Mark", "Virama", "Visarga", "Vowel", "Vowel_Dependent",
        "Vowel_Independent"
    };

    @Test
    void exposesPinnedSourcesAndExactAliases() {
        assertEquals("17.0.0", PerlUnicodeIndicCategoryData.UNICODE_VERSION);
        assertEquals("4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb",
                PerlUnicodeIndicCategoryData.PROPERTY_ALIASES_SHA256);
        assertEquals("670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01",
                PerlUnicodeIndicCategoryData.VALUE_ALIASES_SHA256);
        assertEquals("68cedc29a7e57f984d90fe2c7712f2e6d0c717e253db219607daea8997d6c480",
                PerlUnicodeIndicCategoryData.INDIC_POSITIONAL_SHA256);
        assertEquals("3fc122f4cf58b0c19268d5f810263b04ab4e1e67743386ec0e0ada9c76aec5be",
                PerlUnicodeIndicCategoryData.INDIC_SYLLABIC_SHA256);

        assertTrue(PerlUnicodeIndicCategoryData.isPropertyAlias("InPC"));
        assertTrue(PerlUnicodeIndicCategoryData.isPropertyAlias("indic positional-category"));
        assertTrue(PerlUnicodeIndicCategoryData.isPropertyAlias("InSC"));
        assertTrue(PerlUnicodeIndicCategoryData.isPropertyAlias("INDIC_SYLLABIC_CATEGORY"));
        assertEquals("Indic_Positional_Category",
                PerlUnicodeIndicCategoryData.canonicalProperty("i-n_p c"));
        assertEquals("Indic_Syllabic_Category",
                PerlUnicodeIndicCategoryData.canonicalProperty("InSC"));
        assertFalse(PerlUnicodeIndicCategoryData.isPropertyAlias("Is_InPC"));
        assertFalse(PerlUnicodeIndicCategoryData.isPropertyAlias("\u0130nPC"));
        assertFalse(PerlUnicodeIndicCategoryData.isPropertyAlias("Indic\u1680PositionalCategory"));
        assertNull(PerlUnicodeIndicCategoryData.canonicalProperty("unknown"));
    }

    @Test
    void exposesEveryCanonicalValueAndShortAlias() {
        assertArrayEquals(INPC_VALUES,
                PerlUnicodeIndicCategoryData.canonicalValues("InPC"));
        assertArrayEquals(INPC_VALUES,
                PerlUnicodeIndicCategoryData.wildcardValues("Indic_Positional_Category"));
        assertArrayEquals(INSC_VALUES,
                PerlUnicodeIndicCategoryData.wildcardValues("Indic_Syllabic_Category"));
        assertEquals("Not_Applicable",
                PerlUnicodeIndicCategoryData.canonicalValue("InPC", "NA"));
        assertEquals("NA",
                PerlUnicodeIndicCategoryData.shortValue("InPC", "not applicable"));

        for (String value : INPC_VALUES) {
            assertEquals(value,
                    PerlUnicodeIndicCategoryData.canonicalValue("InPC", value));
        }
        for (String value : INSC_VALUES) {
            assertEquals(value,
                    PerlUnicodeIndicCategoryData.canonicalValue("InSC", value));
        }
        assertNull(PerlUnicodeIndicCategoryData.valueSet("InPC", "unknown"));
        assertNull(PerlUnicodeIndicCategoryData.valueSet("unknown", "Bottom"));
    }

    @Test
    void formsTwoExactDisjointUnicodePartitions() {
        assertPartition("InPC", INPC_VALUES, 884);
        assertPartition("InSC", INSC_VALUES, 1154);
    }

    private static void assertPartition(String property, String[] values, int ranges) {
        assertEquals(ranges, PerlUnicodeIndicCategoryData.rangeCount(property));
        UnicodeSet union = new UnicodeSet();
        for (String value : values) {
            UnicodeSet set = PerlUnicodeIndicCategoryData.valueSet(property, value);
            assertTrue(set.isFrozen(), property + '=' + value + " is frozen");
            assertEquals(PerlUnicodeIndicCategoryData.expectedCardinality(property, value),
                    set.size(), property + '=' + value + " cardinality");
            assertTrue(new UnicodeSet(union).retainAll(set).isEmpty(),
                    property + '=' + value + " is disjoint from preceding values");
            union.addAll(set);
        }
        assertEquals(0x110000, union.size(), property + " covers all code points");
        assertTrue(union.contains(0));
        assertTrue(union.contains(0x10ffff));
    }

    @Test
    void preservesDefaultsBoundariesAndUnicode17Sentinels() {
        assertEquals(1_112_813,
                PerlUnicodeIndicCategoryData.valueSet("InPC", "NA").size());
        assertTrue(PerlUnicodeIndicCategoryData.valueSet("InPC", "NA").contains('A'));
        assertEquals(1_109_256,
                PerlUnicodeIndicCategoryData.valueSet("InSC", "Other").size());
        assertTrue(PerlUnicodeIndicCategoryData.valueSet("InSC", "Other").contains(0x10ffff));

        assertTrue(PerlUnicodeIndicCategoryData.valueSet("InPC", "Top").contains(0x11b60));
        assertTrue(PerlUnicodeIndicCategoryData.valueSet("InPC", "Right").contains(0x11b61));
        assertTrue(PerlUnicodeIndicCategoryData.valueSet("InPC", "Bottom").contains(0x11b62));
        assertTrue(PerlUnicodeIndicCategoryData.valueSet("InSC", "Vowel_Dependent")
                .contains(0x11b67));
        assertFalse(PerlUnicodeIndicCategoryData.valueSet("InSC", "Vowel_Dependent")
                .contains(0x11b68));
    }

    @Test
    void generatorReproducesCheckedInDataWhenPinnedSourcesArePresent() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path source = root.resolve("perl5/lib/unicore/IndicSyllabicCategory.txt");
        Assumptions.assumeTrue(Files.isRegularFile(source),
                "ignored Perl UCD source tree is absent in a clean CI checkout");

        Path generated = Files.createTempFile("perlonjava-indic-category-", ".java");
        Path errors = Files.createTempFile("perlonjava-indic-category-", ".err");
        try {
            Process process = new ProcessBuilder(
                    "perl", "dev/tools/generate_perl_unicode_indic_category_data.pl")
                    .directory(root.toFile())
                    .redirectOutput(generated.toFile())
                    .redirectError(errors.toFile())
                    .start();
            int exit = process.waitFor();
            assertEquals(0, exit, Files.readString(errors, StandardCharsets.UTF_8));
            Path checkedIn = root.resolve(
                    "src/main/java/org/perlonjava/runtime/regex/PerlUnicodeIndicCategoryData.java");
            assertArrayEquals(Files.readAllBytes(checkedIn), Files.readAllBytes(generated));
        } finally {
            Files.deleteIfExists(generated);
            Files.deleteIfExists(errors);
        }
    }
}
