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
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeEgyptianHieroglyphCoreDataTest {
    @Test
    void exposesPinnedPerl544Unicode17Sources() {
        assertEquals("17.0.0", PerlUnicodeEgyptianHieroglyphCoreData.UNICODE_VERSION);
        assertEquals("8c30575264b2772c7a69c5bb6069a28f0e0a7a0df735871bde2d99ee674316ac",
                PerlUnicodeEgyptianHieroglyphCoreData.VERSION_SHA256);
        assertEquals("76a3081265e6eb673873f9c93d6f36062e82c7ed027c5c1a592accfbe48c20a5",
                PerlUnicodeEgyptianHieroglyphCoreData.UNIKEMET_SHA256);
        assertEquals("e3ac360c03d18779fea6d6497fbbe53798135da55e3764d3c9f90a79bbf7e8b5",
                PerlUnicodeEgyptianHieroglyphCoreData.MKTABLES_SHA256);
    }

    @Test
    void resolvesOnlyTheProvisionalPropertyAndItsThreeValues() {
        assertTrue(PerlUnicodeEgyptianHieroglyphCoreData.isPropertyAlias("kEH_Core"));
        assertTrue(PerlUnicodeEgyptianHieroglyphCoreData.isPropertyAlias(
                "k-e h\tc_o r e"));
        assertEquals("kEH_Core",
                PerlUnicodeEgyptianHieroglyphCoreData.canonicalProperty("KEHCORE"));
        assertFalse(PerlUnicodeEgyptianHieroglyphCoreData.isPropertyAlias("Is_kEH_Core"));
        assertFalse(PerlUnicodeEgyptianHieroglyphCoreData.isPropertyAlias(
                "kEH\u1680Core"));
        assertFalse(PerlUnicodeEgyptianHieroglyphCoreData.isPropertyAlias(
                "k\u0130H_Core"));
        assertNull(PerlUnicodeEgyptianHieroglyphCoreData.canonicalProperty("Core"));

        for (String value : new String[] {"C", "L", "N"}) {
            assertEquals(value,
                    PerlUnicodeEgyptianHieroglyphCoreData.canonicalValue(
                            "kehcore", value.toLowerCase()));
            assertEquals(value,
                    PerlUnicodeEgyptianHieroglyphCoreData.shortValue(
                            "kEH_Core", " _" + value + "- "));
            assertTrue(PerlUnicodeEgyptianHieroglyphCoreData
                    .valueSet("kEH_Core", value).isFrozen());
        }
        assertNull(PerlUnicodeEgyptianHieroglyphCoreData.valueSet("unknown", "C"));
        assertNull(PerlUnicodeEgyptianHieroglyphCoreData.valueSet("kEH_Core", "Y"));
        assertNull(PerlUnicodeEgyptianHieroglyphCoreData.canonicalValue("kEH_Core", "Core"));
    }

    @Test
    void exposesDefensiveCanonicalAndWildcardValueLists() {
        String[] expected = {"C", "L", "N"};
        assertArrayEquals(expected,
                PerlUnicodeEgyptianHieroglyphCoreData.canonicalValues("kEH_Core"));
        assertArrayEquals(expected,
                PerlUnicodeEgyptianHieroglyphCoreData.wildcardValues("k e h core"));
        assertNull(PerlUnicodeEgyptianHieroglyphCoreData.canonicalValues("unknown"));
        assertNull(PerlUnicodeEgyptianHieroglyphCoreData.wildcardValues("unknown"));

        String[] copy = PerlUnicodeEgyptianHieroglyphCoreData.canonicalValues("kEH_Core");
        copy[0] = "changed";
        assertEquals("C",
                PerlUnicodeEgyptianHieroglyphCoreData.canonicalValues("kEH_Core")[0]);
    }

    @Test
    void partitionsTheCompleteUnicodeCodeSpaceIntoFrozenDisjointSets() {
        UnicodeSet core = PerlUnicodeEgyptianHieroglyphCoreData.valueSet("kEH_Core", "C");
        UnicodeSet legacy = PerlUnicodeEgyptianHieroglyphCoreData.valueSet("kEH_Core", "L");
        UnicodeSet neither = PerlUnicodeEgyptianHieroglyphCoreData.valueSet("kEH_Core", "N");

        assertTrue(core.isFrozen());
        assertTrue(legacy.isFrozen());
        assertTrue(neither.isFrozen());
        assertEquals(541, core.getRangeCount());
        assertEquals(68, legacy.getRangeCount());
        assertEquals(474, neither.getRangeCount());
        assertEquals(4_403, core.size());
        assertEquals(96, legacy.size());
        assertEquals(1_109_613, neither.size());
        assertEquals(541,
                PerlUnicodeEgyptianHieroglyphCoreData.expectedRangeCount("C"));
        assertEquals(68,
                PerlUnicodeEgyptianHieroglyphCoreData.expectedRangeCount("L"));
        assertEquals(474,
                PerlUnicodeEgyptianHieroglyphCoreData.expectedRangeCount("N"));
        assertEquals(4_403,
                PerlUnicodeEgyptianHieroglyphCoreData.expectedCardinality("C"));
        assertEquals(96,
                PerlUnicodeEgyptianHieroglyphCoreData.expectedCardinality("L"));
        assertEquals(1_109_613,
                PerlUnicodeEgyptianHieroglyphCoreData.expectedCardinality("N"));
        assertEquals(-1,
                PerlUnicodeEgyptianHieroglyphCoreData.expectedCardinality("unknown"));

        assertFalse(core.containsSome(legacy));
        assertFalse(core.containsSome(neither));
        assertFalse(legacy.containsSome(neither));
        UnicodeSet union = new UnicodeSet(core).addAll(legacy).addAll(neither);
        assertEquals(0x110000, union.size());
        assertTrue(union.contains(0, 0x10ffff));
    }

    @Test
    void preservesBoundariesDefaultsAndUnicode17Changes() {
        assertValue(0x12fff, "N");
        assertValue(0x13000, "C");
        assertValue(0x1305c, "C");
        assertValue(0x1305d, "L");
        assertValue(0x1305e, "L");
        assertValue(0x1305f, "C");
        assertValue(0x13423, "C");
        assertValue(0x13424, "L");
        assertValue(0x13425, "C");
        assertValue(0x143f9, "N");
        assertValue(0x143fa, "C");
        assertValue(0x143fb, "N");
        assertValue(0xd800, "N");
        assertValue(0x10ffff, "N");

        assertValue(0x130a9, "C");
        assertValue(0x136ae, "N");
        assertValue(0x142ad, "N");
    }

    @Test
    void generatorReproducesCheckedInDataWhenPinnedSourcesArePresent() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path snapshot = root.resolve("dev/unicode/17.0.0");
        Path perl = root.resolve("perl5/lib/unicore");
        boolean unicodeSources = Files.isRegularFile(snapshot.resolve("version"))
                        && Files.isRegularFile(snapshot.resolve("Unikemet.txt"))
                || Files.isRegularFile(perl.resolve("version"))
                        && Files.isRegularFile(perl.resolve("Unikemet.txt"));
        boolean perlPolicy = Files.isRegularFile(snapshot.resolve("mktables"))
                || Files.isRegularFile(perl.resolve("mktables"));
        Assumptions.assumeTrue(unicodeSources && perlPolicy,
                "pinned Perl Unicode sources are unavailable in this checkout");

        Process process = new ProcessBuilder(
                "perl", "dev/tools/generate_perl_unicode_egyptian_hieroglyph_core_data.pl")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(Duration.ofSeconds(30)), "generator timed out");
        ByteArrayOutputStream generated = new ByteArrayOutputStream();
        process.getInputStream().transferTo(generated);
        assertEquals(0, process.exitValue(), generated.toString(StandardCharsets.UTF_8));
        byte[] checkedIn = Files.readAllBytes(root.resolve(
                "src/main/java/org/perlonjava/runtime/regex/"
                        + "PerlUnicodeEgyptianHieroglyphCoreData.java"));
        assertArrayEquals(checkedIn, generated.toByteArray());
    }

    private static void assertValue(int codePoint, String expectedValue) {
        for (String value : List.of("C", "L", "N")) {
            assertEquals(value.equals(expectedValue),
                    PerlUnicodeEgyptianHieroglyphCoreData
                            .valueSet("kEH_Core", value).contains(codePoint),
                    String.format("U+%04X has kEH_Core=%s", codePoint, value));
        }
    }
}
