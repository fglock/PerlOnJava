package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
class PerlUnicodeIdentifierDataTest {
    private static final String[] STATUS_VALUES = {"Allowed", "Restricted"};
    private static final String[] TYPE_VALUES = {
        "Not_Character", "Deprecated", "Default_Ignorable", "Not_NFKC",
        "Not_XID", "Exclusion", "Obsolete", "Technical", "Uncommon_Use",
        "Limited_Use", "Inclusion", "Recommended"
    };

    @Test
    void exposesPinnedSourcesAndLooseAliases() {
        assertEquals("17.0.0", PerlUnicodeIdentifierData.UNICODE_VERSION);
        assertEquals("617228a16da13850bf8af28b6cd08f5e9b6595d2eb60404fe6eee2c85b4e4a35",
                PerlUnicodeIdentifierData.IDENTIFIER_STATUS_SHA256);
        assertEquals("924ac63faa97ed73420d6ac48d08279d90968c7da0502ab701e08bfbb9683c22",
                PerlUnicodeIdentifierData.IDENTIFIER_TYPE_SHA256);

        assertTrue(PerlUnicodeIdentifierData.isPropertyAlias("ID_Status"));
        assertTrue(PerlUnicodeIdentifierData.isPropertyAlias("identifier status"));
        assertTrue(PerlUnicodeIdentifierData.isPropertyAlias("ID-Type"));
        assertEquals("Identifier_Status",
                PerlUnicodeIdentifierData.canonicalProperty("i d-s_tatus"));
        assertEquals("Identifier_Type",
                PerlUnicodeIdentifierData.canonicalProperty("IDENTIFIER_TYPE"));
        assertFalse(PerlUnicodeIdentifierData.isPropertyAlias("Is_ID_Status"));
        assertFalse(PerlUnicodeIdentifierData.isPropertyAlias("Identifier\u1680Type"));
        assertNull(PerlUnicodeIdentifierData.canonicalProperty("unknown"));
    }

    @Test
    void exposesCanonicalValuesWithDefensiveArrays() {
        assertArrayEquals(STATUS_VALUES,
                PerlUnicodeIdentifierData.canonicalValues("ID_Status"));
        assertArrayEquals(TYPE_VALUES,
                PerlUnicodeIdentifierData.wildcardValues("Identifier_Type"));
        assertEquals("Default_Ignorable",
                PerlUnicodeIdentifierData.canonicalValue("ID_Type", "default-ignorable"));
        assertEquals("Not_Character",
                PerlUnicodeIdentifierData.canonicalValue("Identifier Type", "not character"));
        assertNull(PerlUnicodeIdentifierData.canonicalValue("ID_Type", "unknown"));
        assertNull(PerlUnicodeIdentifierData.valueSet("unknown", "Allowed"));

        String[] first = PerlUnicodeIdentifierData.canonicalValues("ID_Type");
        String[] second = PerlUnicodeIdentifierData.canonicalValues("ID_Type");
        assertNotSame(first, second);
        first[0] = "mutated";
        assertArrayEquals(TYPE_VALUES, second);
    }

    @Test
    void statusFormsCompleteDisjointPartition() {
        UnicodeSet allowed = PerlUnicodeIdentifierData.valueSet("ID_Status", "Allowed");
        UnicodeSet restricted =
                PerlUnicodeIdentifierData.valueSet("Identifier_Status", "Restricted");
        assertTrue(allowed.isFrozen());
        assertTrue(restricted.isFrozen());
        assertEquals(33_791, allowed.size());
        assertEquals(1_080_321, restricted.size());
        assertEquals(1_612,
                PerlUnicodeIdentifierData.rangeCount("ID_Status", "Allowed"));
        assertEquals(1_613,
                PerlUnicodeIdentifierData.rangeCount("ID_Status", "Restricted"));
        assertTrue(new UnicodeSet(allowed).retainAll(restricted).isEmpty());
        assertEquals(0x110000, new UnicodeSet(allowed).addAll(restricted).size());
        assertTrue(allowed.contains('A'));
        assertTrue(restricted.contains(0));
        assertTrue(restricted.contains(0x10ffff));
    }

    @Test
    void typePreservesOverlapsAndNotCharacterDefault() {
        for (String value : TYPE_VALUES) {
            UnicodeSet set = PerlUnicodeIdentifierData.valueSet("ID_Type", value);
            assertTrue(set.isFrozen(), value + " is frozen");
            assertEquals(PerlUnicodeIdentifierData.expectedCardinality("ID_Type", value),
                    set.size(), value + " cardinality");
        }

        UnicodeSet notCharacter =
                PerlUnicodeIdentifierData.valueSet("ID_Type", "Not_Character");
        assertEquals(954_305, notCharacter.size());
        assertEquals(737,
                PerlUnicodeIdentifierData.rangeCount("ID_Type", "Not_Character"));
        assertTrue(notCharacter.contains(0));
        assertFalse(notCharacter.contains('A'));
        assertTrue(PerlUnicodeIdentifierData.valueSet("ID_Type", "Recommended")
                .contains('A'));

        assertTrue(PerlUnicodeIdentifierData.valueSet("ID_Type", "Technical")
                .contains(0x018d));
        assertTrue(PerlUnicodeIdentifierData.valueSet("ID_Type", "Obsolete")
                .contains(0x018d));
        assertTrue(PerlUnicodeIdentifierData.valueSet("ID_Type", "Limited_Use")
                .contains(0x1cc0));
        assertTrue(PerlUnicodeIdentifierData.valueSet("ID_Type", "Not_XID")
                .contains(0x1cc0));
    }

    @Test
    void preservesUnicode17Sentinels() {
        assertTrue(PerlUnicodeIdentifierData.valueSet("ID_Type", "Exclusion")
                .contains(0x11b60));
        assertTrue(PerlUnicodeIdentifierData.valueSet("ID_Type", "Not_XID")
                .contains(0x1ccfa));
        assertFalse(PerlUnicodeIdentifierData.valueSet("ID_Type", "Not_Character")
                .contains(0x11b60));
    }

    @Test
    void generatorReproducesCheckedInDataWhenPinnedSourcesArePresent() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path source = root.resolve("perl5/lib/unicore/IdType.txt");
        Assumptions.assumeTrue(Files.isRegularFile(source),
                "ignored Perl UCD source tree is absent in a clean CI checkout");

        Path generated = Files.createTempFile("perlonjava-identifier-", ".java");
        Path errors = Files.createTempFile("perlonjava-identifier-", ".err");
        try {
            Process process = new ProcessBuilder(
                    "perl", "dev/tools/generate_perl_unicode_identifier_data.pl")
                    .directory(root.toFile())
                    .redirectOutput(generated.toFile())
                    .redirectError(errors.toFile())
                    .start();
            int exit = process.waitFor();
            assertEquals(0, exit, Files.readString(errors, StandardCharsets.UTF_8));
            Path checkedIn = root.resolve(
                    "src/main/java/org/perlonjava/runtime/regex/PerlUnicodeIdentifierData.java");
            assertArrayEquals(Files.readAllBytes(checkedIn), Files.readAllBytes(generated));
        } finally {
            Files.deleteIfExists(generated);
            Files.deleteIfExists(errors);
        }
    }
}
