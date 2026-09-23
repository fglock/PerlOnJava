package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeScalarNameDataTest {

    @Test
    void retainsCanonicalNamesAndEveryAliasFamilyFromNamePl() {
        assertEquals(35_079, PerlUnicodeScalarNameData.entryCount());
        assertEquals(0, PerlUnicodeScalarNameData.DUPLICATE_NAME_COUNT);
        assertEquals(0x00ad, PerlUnicodeScalarNameData.codePoint("SOFT HYPHEN"));
        assertEquals(0x00ad, PerlUnicodeScalarNameData.codePoint("SHY"));
        assertEquals(0x000a, PerlUnicodeScalarNameData.codePoint("LINE FEED (LF)"));
        assertEquals(0x000a, PerlUnicodeScalarNameData.codePoint("NEW LINE"));
        assertEquals(0x01a2, PerlUnicodeScalarNameData.codePoint(
                "LATIN CAPITAL LETTER GHA"));
        assertEquals(0x01a2, PerlUnicodeScalarNameData.codePoint(
                "LATIN CAPITAL LETTER OI"));
        assertEquals(0xfeff, PerlUnicodeScalarNameData.codePoint("BOM"));
        assertEquals(-1, PerlUnicodeScalarNameData.codePoint("NOT A CHARACTER NAME"));
        assertEquals(-1, PerlUnicodeScalarNameData.codePoint(null));
    }

    @Test
    void generatorIsDeterministicAndFresh() throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        Path generator = root.resolve(
                "dev/regex/tools/generate_perl_unicode_scalar_name_data.pl");
        Path generated = root.resolve(
                "src/main/java/org/perlonjava/runtime/regex/PerlUnicodeScalarNameData.java");

        byte[] first = generate(root, generator);
        byte[] second = generate(root, generator);
        assertArrayEquals(first, second, "generator output must be deterministic");
        assertArrayEquals(canonicalLf(Files.readAllBytes(generated)), canonicalLf(first),
                "checked-in scalar-name data must match current imported Name.pl");
    }

    private static byte[] generate(Path root, Path generator) throws Exception {
        Process process = new ProcessBuilder("perl", generator.toString())
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        assertEquals(0, process.waitFor(), new String(output.toByteArray()));
        return output.toByteArray();
    }

    private static byte[] canonicalLf(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .getBytes(StandardCharsets.UTF_8);
    }
}
