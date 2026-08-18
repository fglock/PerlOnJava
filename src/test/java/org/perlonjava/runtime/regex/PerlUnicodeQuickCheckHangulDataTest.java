package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.icu.text.UnicodeSet;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeQuickCheckHangulDataTest {
    private static final Map<String, String[]> VALUES = Map.of(
            "hst", new String[] {
                "Leading_Jamo", "LV_Syllable", "LVT_Syllable",
                "Not_Applicable", "Trailing_Jamo", "Vowel_Jamo"
            },
            "NFC_QC", new String[] {"Maybe", "No", "Yes"},
            "NFD_QC", new String[] {"No", "Yes"},
            "NFKC_QC", new String[] {"Maybe", "No", "Yes"},
            "NFKD_QC", new String[] {"No", "Yes"});

    @Test
    void exposesPinnedUnicode17Sources() {
        assertEquals("17.0.0", PerlUnicodeQuickCheckHangulData.UNICODE_VERSION);
        assertEquals("4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb",
                PerlUnicodeQuickCheckHangulData.PROPERTY_ALIASES_SHA256);
        assertEquals("670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01",
                PerlUnicodeQuickCheckHangulData.VALUE_ALIASES_SHA256);
        assertEquals("5a57450afde0d082bc5026f7458649eac3b615490cc7e3d916b0367f1593c0e3",
                PerlUnicodeQuickCheckHangulData.HANGUL_SYLLABLE_TYPE_SHA256);
        assertEquals("71fd6a206a2c0cdd41feb6b7f656aa31091db45e9cedc926985d718397f9e488",
                PerlUnicodeQuickCheckHangulData.NORMALIZATION_PROPS_SHA256);
    }

    @Test
    void resolvesPropertyAndValueAliasesWithPerlLooseMatching() {
        assertTrue(PerlUnicodeQuickCheckHangulData.isPropertyAlias("hst"));
        assertTrue(PerlUnicodeQuickCheckHangulData.isPropertyAlias("Hangul Syllable-Type"));
        assertTrue(PerlUnicodeQuickCheckHangulData.isPropertyAlias("n f k c _ q c"));
        assertEquals("Hangul_Syllable_Type",
                PerlUnicodeQuickCheckHangulData.canonicalProperty("H-S-T"));
        assertEquals("NFC_Quick_Check",
                PerlUnicodeQuickCheckHangulData.canonicalProperty("nfc qc"));
        assertFalse(PerlUnicodeQuickCheckHangulData.isPropertyAlias("Is_hst"));
        assertFalse(PerlUnicodeQuickCheckHangulData.isPropertyAlias("\u0130D_Syllable_Type"));
        assertFalse(PerlUnicodeQuickCheckHangulData.isPropertyAlias("Hangul\u1680Syllable_Type"));
        assertNull(PerlUnicodeQuickCheckHangulData.canonicalProperty("not_a_property"));

        assertEquals("Leading_Jamo",
                PerlUnicodeQuickCheckHangulData.canonicalValue("hst", "l"));
        assertEquals("Leading_Jamo",
                PerlUnicodeQuickCheckHangulData.canonicalValue("hst", "leading-jamo"));
        assertEquals("NA",
                PerlUnicodeQuickCheckHangulData.shortValue("hst", "Not Applicable"));
        assertEquals("M",
                PerlUnicodeQuickCheckHangulData.shortValue("NFC_QC", "Maybe"));
        assertNull(PerlUnicodeQuickCheckHangulData.valueSet("NFD_QC", "Maybe"));
        assertNull(PerlUnicodeQuickCheckHangulData.valueSet("unknown", "Yes"));
    }

    @Test
    void exposesCanonicalAndWildcardValuesInPinnedOrder() {
        for (Map.Entry<String, String[]> entry : VALUES.entrySet()) {
            assertArrayEquals(entry.getValue(),
                    PerlUnicodeQuickCheckHangulData.canonicalValues(entry.getKey()));
            assertArrayEquals(entry.getValue(),
                    PerlUnicodeQuickCheckHangulData.wildcardValues(entry.getKey()));
        }
        assertNull(PerlUnicodeQuickCheckHangulData.canonicalValues("unknown"));

        String[] copy = PerlUnicodeQuickCheckHangulData.canonicalValues("NFC_QC");
        copy[0] = "changed";
        assertEquals("Maybe",
                PerlUnicodeQuickCheckHangulData.canonicalValues("NFC_QC")[0]);
    }

    @Test
    void partitionsTheEntireUnicodeCodeSpaceIntoFrozenDisjointSets() {
        for (Map.Entry<String, String[]> entry : VALUES.entrySet()) {
            String property = entry.getKey();
            List<UnicodeSet> sets = new ArrayList<>();
            UnicodeSet union = new UnicodeSet();
            for (String value : entry.getValue()) {
                UnicodeSet set = PerlUnicodeQuickCheckHangulData.valueSet(property, value);
                assertTrue(set.isFrozen(), property + "=" + value + " is frozen");
                assertEquals(
                        PerlUnicodeQuickCheckHangulData.expectedCardinality(property, value),
                        set.size(), property + "=" + value + " cardinality");
                for (UnicodeSet previous : sets) {
                    assertTrue(new UnicodeSet(set).retainAll(previous).isEmpty(),
                            property + " value sets are disjoint");
                }
                sets.add(set);
                union.addAll(set);
            }
            assertEquals(0x110000, union.size(), property + " covers Unicode");
            assertTrue(union.contains(0, 0x10ffff), property + " has no gaps");
        }
        assertEquals(810, PerlUnicodeQuickCheckHangulData.rangeCount("hst"));
        assertEquals(242, PerlUnicodeQuickCheckHangulData.rangeCount("NFC_QC"));
        assertEquals(485, PerlUnicodeQuickCheckHangulData.rangeCount("NFD_QC"));
        assertEquals(607, PerlUnicodeQuickCheckHangulData.rangeCount("NFKC_QC"));
        assertEquals(817, PerlUnicodeQuickCheckHangulData.rangeCount("NFKD_QC"));
        assertEquals(-1, PerlUnicodeQuickCheckHangulData.rangeCount("unknown"));
    }

    @Test
    void preservesHangulBoundariesAndDefaults() {
        assertMembership("hst", "Not_Applicable", 0x10ff, 0x1200, 0xa95f, 0xa97d,
                0xd7a4, 0xd7af, 0xd7c7, 0xd7ca, 0xd7fc, 0x10ffff);
        assertMembership("hst", "Leading_Jamo", 0x1100, 0x115f, 0xa960, 0xa97c);
        assertMembership("hst", "Vowel_Jamo", 0x1160, 0x11a7, 0xd7b0, 0xd7c6);
        assertMembership("hst", "Trailing_Jamo", 0x11a8, 0x11ff, 0xd7cb, 0xd7fb);
        assertMembership("hst", "LV_Syllable", 0xac00, 0xac1c, 0xd788);
        assertMembership("hst", "LVT_Syllable", 0xac01, 0xac1b, 0xd789, 0xd7a3);
        assertFalse(PerlUnicodeQuickCheckHangulData.valueSet("hst", "LV").contains(0xac01));
        assertFalse(PerlUnicodeQuickCheckHangulData.valueSet("hst", "LVT").contains(0xac00));
    }

    @Test
    void preservesQuickCheckSentinelsAndUnicode17Changes() {
        assertQuickChecks(0x0041, "Yes", "Yes", "Yes", "Yes");
        assertQuickChecks(0x00a0, "Yes", "Yes", "No", "No");
        assertQuickChecks(0x00c0, "Yes", "No", "Yes", "No");
        assertQuickChecks(0x0300, "Maybe", "Yes", "Maybe", "Yes");
        assertQuickChecks(0x0340, "No", "No", "No", "No");
        assertQuickChecks(0xa7f1, "Yes", "Yes", "No", "No");
        assertQuickChecks(0x2fa1d, "No", "No", "No", "No");
        assertQuickChecks(0x2fa1e, "Yes", "Yes", "Yes", "Yes");
        assertQuickChecks(0xd800, "Yes", "Yes", "Yes", "Yes");
        assertQuickChecks(0x10ffff, "Yes", "Yes", "Yes", "Yes");
    }

    @Test
    void generatorReproducesCheckedInDataWhenPinnedSourcesArePresent() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path sourceRoot = root.resolve("perl5/lib/unicore");
        List<String> inputs = List.of(
                "version", "PropertyAliases.txt", "PropValueAliases.txt",
                "HangulSyllableType.txt", "DNormalizationProps.txt");
        Assumptions.assumeTrue(inputs.stream().allMatch(name -> Files.isRegularFile(sourceRoot.resolve(name))),
                "pinned Perl Unicode source tree is not present");

        Process process = new ProcessBuilder(
                "perl", "dev/tools/generate_perl_unicode_quick_check_hangul_data.pl")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(Duration.ofSeconds(30)), "generator timed out");
        ByteArrayOutputStream generated = new ByteArrayOutputStream();
        process.getInputStream().transferTo(generated);
        assertEquals(0, process.exitValue(), generated.toString(StandardCharsets.UTF_8));
        byte[] checkedIn = Files.readAllBytes(root.resolve(
                "src/main/java/org/perlonjava/runtime/regex/PerlUnicodeQuickCheckHangulData.java"));
        assertArrayEquals(checkedIn, generated.toByteArray());
    }

    private static void assertMembership(String property, String value, int... codePoints) {
        UnicodeSet set = PerlUnicodeQuickCheckHangulData.valueSet(property, value);
        for (int codePoint : codePoints) {
            assertTrue(set.contains(codePoint),
                    property + "=" + value + " contains U+" + Integer.toHexString(codePoint));
        }
    }

    private static void assertQuickChecks(
            int codePoint, String nfc, String nfd, String nfkc, String nfkd) {
        assertTrue(PerlUnicodeQuickCheckHangulData.valueSet("NFC_QC", nfc).contains(codePoint));
        assertTrue(PerlUnicodeQuickCheckHangulData.valueSet("NFD_QC", nfd).contains(codePoint));
        assertTrue(PerlUnicodeQuickCheckHangulData.valueSet("NFKC_QC", nfkc).contains(codePoint));
        assertTrue(PerlUnicodeQuickCheckHangulData.valueSet("NFKD_QC", nfkd).contains(codePoint));
    }
}
