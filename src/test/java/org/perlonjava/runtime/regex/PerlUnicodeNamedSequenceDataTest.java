package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeNamedSequenceDataTest {

    @Test
    void usesPinnedPerlUnicode17NamedSequences() {
        assertEquals("17.0.0", PerlUnicodeNamedSequenceData.UNICODE_VERSION);
        assertEquals("NamedSequences-17.0.0.txt",
                PerlUnicodeNamedSequenceData.SOURCE_FILE);
        assertEquals(461, PerlUnicodeNamedSequenceData.entryCount());
        assertEquals("9\uFE0F\u20E3",
                PerlUnicodeNamedSequenceData.sequence("KEYCAP DIGIT NINE"));
        assertEquals("\u0100\u0300", PerlUnicodeNamedSequenceData.sequence(
                "LATIN CAPITAL LETTER A WITH MACRON AND GRAVE"));
        assertNull(PerlUnicodeNamedSequenceData.sequence("LATIN CAPITAL LETTER A"));
        assertNull(PerlUnicodeNamedSequenceData.sequence("UNKNOWN SEQUENCE"));
        assertNull(PerlUnicodeNamedSequenceData.sequence(null));
    }

    @Test
    void generatorReproducesTheCheckedInClassByteForByte() throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        Path generator = root.resolve(
                "dev/regex/tools/generate_perl_unicode_named_sequence_data.pl");
        Path generated = root.resolve(
                "src/main/java/org/perlonjava/runtime/regex/PerlUnicodeNamedSequenceData.java");

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
