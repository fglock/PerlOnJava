package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ibm.icu.text.UnicodeSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeBlockDataTest {
    private static final int MAX_CODE_POINT = 0x10ffff;

    @Test
    void usesPinnedPerl544Unicode17Data() {
        assertEquals("17.0.0", PerlUnicodeBlockData.UNICODE_VERSION);
        assertEquals(347, PerlUnicodeBlockData.valueCount());
        assertEquals(495, PerlUnicodeBlockData.aliasCount());
        assertEquals(397, PerlUnicodeBlockData.rangeCount());
        assertEquals("No_Block",
                PerlUnicodeBlockData.canonicalValue(PerlUnicodeBlockData.NO_BLOCK));

        assertRange("Sidetic", 0x10940, 0x1095f);
        assertRange("Sharada_Sup", 0x11b60, 0x11b7f);
        assertRange("Tolong_Siki", 0x11db0, 0x11def);
        assertRange("Beria_Erfe", 0x16ea0, 0x16edf);
        assertRange("Tangut_Components_Sup", 0x18d80, 0x18dff);
        assertRange("Misc_Symbols_Sup", 0x1cec0, 0x1ceff);
        assertRange("Tai_Yo", 0x1e6c0, 0x1e6ff);
        assertRange("CJK_Ext_J", 0x323b0, 0x3347f);
    }

    @Test
    void formsTheCompleteDisjointBlockPartition() {
        UnicodeSet union = new UnicodeSet();
        Set<String> names = new HashSet<>();
        int namedCodePoints = 0;
        int rangeCount = 0;

        for (int valueId = 0; valueId < PerlUnicodeBlockData.valueCount(); valueId++) {
            String name = PerlUnicodeBlockData.canonicalValue(valueId);
            UnicodeSet set = PerlUnicodeBlockData.set(valueId);
            assertTrue(names.add(name), name);
            assertTrue(set.isFrozen(), name);
            assertFalse(set.isEmpty(), name);
            assertTrue(new UnicodeSet(union).retainAll(set).isEmpty(), name);
            union.addAll(set);
            rangeCount += set.getRangeCount();
            if (valueId != PerlUnicodeBlockData.NO_BLOCK) {
                assertEquals(1, set.getRangeCount(), name);
                namedCodePoints += set.size();
            }
        }

        assertEquals(347, names.size());
        assertEquals(397, rangeCount);
        assertEquals(303_808, namedCodePoints);
        assertEquals(new UnicodeSet(0, MAX_CODE_POINT), union);
    }

    @Test
    void noBlockIsThePinnedDefaultComplement() {
        UnicodeSet noBlock = PerlUnicodeBlockData.set(PerlUnicodeBlockData.NO_BLOCK);
        assertEquals(51, noBlock.getRangeCount());
        assertEquals(810_304, noBlock.size());
        assertTrue(noBlock.contains(0x2fe0));
        assertTrue(noBlock.contains(0x33480));
        assertFalse(noBlock.contains(0x2f00));

        assertTrue(set("Greek_And_Coptic").contains(0x0378),
                "unassigned code points retain their enclosing block");
        assertTrue(set("Arabic_Presentation_Forms_A").contains(0xfdd0),
                "noncharacters retain their enclosing block");
        assertTrue(set("Specials").contains(0xfffe));
        assertTrue(set("Sup_PUA_B").contains(MAX_CODE_POINT));
    }

    @Test
    void resolvesEveryAliasFamilyWithPerlLooseMatching() {
        assertSame(set("Basic_Latin"), set("ASCII"));
        assertSame(set("Basic_Latin"), set("b-a s_i c_latin"));
        assertSame(set("Latin_1_Supplement"), set("Latin1Sup"));
        assertSame(set("Greek_And_Coptic"), set("Greek"));
        assertSame(set("No_Block"), set("NB"));

        assertSame(set("Arabic_Presentation_Forms_A"),
                set("Arabic_Presentation_Forms-A"));
        assertSame(set("Unified_Canadian_Aboriginal_Syllabics"),
                set("Canadian_Syllabics"));
        assertSame(set("Combining_Diacritical_Marks_For_Symbols"),
                set("Combining_Marks_For_Symbols"));
        assertSame(set("Cyrillic_Supplement"), set("Cyrillic_Supplementary"));
        assertSame(set("Latin_1_Supplement"), set("Latin_1"));
        assertSame(set("Private_Use_Area"), set("Private_Use"));

        assertNull(PerlUnicodeBlockData.set("Definitely_Not_A_Block"));
        assertEquals(PerlUnicodeBlockData.INVALID,
                PerlUnicodeBlockData.value("Definitely_Not_A_Block"));
    }

    @Test
    void followsPerlLoosePropertyAliasRules() {
        assertTrue(PerlUnicodeBlockData.isPropertyAlias("blk"));
        assertTrue(PerlUnicodeBlockData.isPropertyAlias("Block"));
        assertTrue(PerlUnicodeBlockData.isPropertyAlias("b_l-k"));
        assertTrue(PerlUnicodeBlockData.isPropertyAlias("IsBlock"));
        assertTrue(PerlUnicodeBlockData.isPropertyAlias("Is_Block"));
        assertTrue(PerlUnicodeBlockData.isPropertyAlias("Isblock"));

        assertFalse(PerlUnicodeBlockData.isPropertyAlias("isBlock"));
        assertFalse(PerlUnicodeBlockData.isPropertyAlias("ISBlock"));
        assertFalse(PerlUnicodeBlockData.isPropertyAlias("Block\u1680"));
        assertFalse(PerlUnicodeBlockData.isPropertyAlias("Blocks"));
        assertFalse(PerlUnicodeBlockData.isPropertyAlias(null));
    }

    @Test
    void generatorReproducesCheckedInDataByteForByte() throws Exception {
        Path generator = Path.of("dev", "regex", "tools",
                "generate_perl_unicode_block_data.pl");
        Path generated = Path.of("src", "main", "java", "org", "perlonjava", "runtime",
                "regex", "PerlUnicodeBlockData.java");
        Process process = new ProcessBuilder("perl", generator.toString())
                .redirectErrorStream(true)
                .start();
        byte[] actual = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "generator timed out");
        if (process.exitValue() != 0
                && !hasExplicitGeneratorSource()
                && reportsMissingDevelopmentSources(actual)) {
            assumeTrue(false, new String(actual, StandardCharsets.UTF_8).trim());
        }
        assertEquals(0, process.exitValue(), new String(actual, StandardCharsets.UTF_8));
        assertArrayEquals(Files.readAllBytes(generated), actual);
        for (byte value : actual) {
            assertFalse(value == '\r', "generated source must use raw LF");
        }
    }

    private static boolean hasExplicitGeneratorSource() {
        return hasText(System.getenv("PERLONJAVA_UNICODE_ROOT"))
                || hasText(System.getenv("PERLONJAVA_PERL_ROOT"));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean reportsMissingDevelopmentSources(byte[] output) {
        String diagnostic = new String(output, StandardCharsets.UTF_8);
        return diagnostic.startsWith("No complete Unicode current source tree:")
                || diagnostic.startsWith("No complete current Perl source tree:");
    }

    private static UnicodeSet set(String alias) {
        UnicodeSet set = PerlUnicodeBlockData.set(alias);
        if (set == null) throw new AssertionError("Unknown Block alias " + alias);
        return set;
    }

    private static void assertRange(String alias, int first, int last) {
        UnicodeSet set = set(alias);
        assertEquals(1, set.getRangeCount(), alias);
        assertEquals(first, set.getRangeStart(0), alias);
        assertEquals(last, set.getRangeEnd(0), alias);
        assertFalse(set.contains(first - 1), alias);
        assertFalse(set.contains(last + 1), alias);
    }
}
