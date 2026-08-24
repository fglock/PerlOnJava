package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.ibm.icu.text.UnicodeSet;
import org.junit.jupiter.api.Test;

class PerlUnicodeUnikemetDataTest {
    @Test
    void preservesPinnedSourceMetadataAndCounts() {
        assertEquals("17.0.0", PerlUnicodeUnikemetData.UNICODE_VERSION);
        assertEquals("2025-07-21", PerlUnicodeUnikemetData.SOURCE_DATE);
        assertEquals("76a3081265e6eb673873f9c93d6f36062e82c7ed027c5c1a592accfbe48c20a5",
                PerlUnicodeUnikemetData.UNIKEMET_SHA256);
        assertEquals(4, PerlUnicodeUnikemetData.NO_MIRROR_CODE_POINT_COUNT);
        assertEquals(44, PerlUnicodeUnikemetData.NO_ROTATE_CODE_POINT_COUNT);
        assertEquals(4, PerlUnicodeUnikemetData.noMirrorRangeCount());
        assertEquals(36, PerlUnicodeUnikemetData.noRotateRangeCount());
    }

    @Test
    void exposesExactFrozenBinaryPropertySets() {
        var noMirror = PerlUnicodeUnikemetData.noMirror();
        var noRotate = PerlUnicodeUnikemetData.noRotate();

        assertTrue(noMirror.isFrozen());
        assertTrue(noRotate.isFrozen());
        assertEquals(4, noMirror.size());
        assertEquals(44, noRotate.size());
        assertTrue(noMirror.contains(0x13081));
        assertTrue(noMirror.contains(0x13084));
        assertTrue(noMirror.contains(0x130BB));
        assertTrue(noMirror.contains(0x130BD));
        assertFalse(noMirror.contains(0x13082));
        assertTrue(noRotate.contains(0x13021));
        assertTrue(noRotate.contains(0x1329B));
        assertTrue(noRotate.contains(0x1329C));
        assertTrue(noRotate.contains(0x14399));
        assertTrue(noRotate.contains(0x1439B));
        assertTrue(noRotate.contains(0x143E8));
        assertFalse(noRotate.contains(0x1439C));
        assertTrue(new UnicodeSet(noMirror).retainAll(noRotate).isEmpty());
    }

    @Test
    void generatorReproducesTheCheckedInClassByteForByte() throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        Path generator = root.resolve("dev/regex/tools/generate_perl_unicode_unikemet_data.pl");
        Path generated = root.resolve(
                "src/main/java/org/perlonjava/runtime/regex/PerlUnicodeUnikemetData.java");

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
