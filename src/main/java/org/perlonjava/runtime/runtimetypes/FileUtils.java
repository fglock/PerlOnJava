package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.app.cli.CompilerOptions;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility class for file operations with encoding detection.
 */
public class FileUtils {

    /**
     * Reads a file with automatic encoding detection based on BOM (Byte Order Mark).
     * Supports UTF-8, UTF-16BE, UTF-16LE, and defaults to UTF-8 if no BOM is found.
     *
     * @param filePath The path to the file to read
     * @return The decoded string content of the file
     * @throws IOException if the file cannot be read
     */
    public static String readFileWithEncodingDetection(Path filePath, CompilerOptions parsedArgs) throws IOException {
        byte[] bytes = Files.readAllBytes(filePath);
        return detectEncodingAndDecode(bytes, parsedArgs);
    }

    /**
     * Detects the encoding of file content based on BOM and heuristics, then decodes it.
     *
     * @param bytes The file content as bytes
     * @return The decoded string content
     */
    private static String detectEncodingAndDecode(byte[] bytes, CompilerOptions parsedArgs) {
        if (bytes.length == 0) {
            return "";
        }

        Charset charset = null;
        int offset = 0;

        // Check for BOM (Byte Order Mark)
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            // UTF-8 BOM
            charset = StandardCharsets.UTF_8;
            offset = 3;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
            // UTF-16BE BOM
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            // UTF-16LE BOM
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else {
            // No BOM - try to detect UTF-16 without BOM using heuristics
            charset = detectCharsetWithoutBOM(bytes);
            offset = 0;
        }

        // Store raw bytes (after BOM removal) for DATA section extraction.
        // In Perl 5, <DATA> reads raw bytes from the file. We preserve the original
        // bytes so the DATA section can provide raw bytes instead of UTF-8-decoded content.
        if (offset > 0) {
            parsedArgs.rawCodeBytes = java.util.Arrays.copyOfRange(bytes, offset, bytes.length);
        } else {
            parsedArgs.rawCodeBytes = bytes;
        }

        // For UTF-16 encodings, use a decoder that can handle malformed input
        // This is needed to preserve invalid surrogate sequences that Perl allows
        if (charset == StandardCharsets.UTF_16LE || charset == StandardCharsets.UTF_16BE) {
            parsedArgs.codeHasEncoding = true;
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);

            try {
                ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
                CharBuffer result = decoder.decode(buffer);
                return result.toString();
            } catch (CharacterCodingException e) {
                // Fall back to default behavior
                return new String(bytes, offset, bytes.length - offset, charset);
            }
        }

        // When source is detected as ISO-8859-1, mark it as byte string source.
        // ISO-8859-1 is a 1:1 mapping from bytes to characters (0x00-0xFF),
        // so the Java string already represents the raw byte values correctly.
        // The string parser should not re-encode these characters to UTF-8.
        if (charset == StandardCharsets.ISO_8859_1) {
            parsedArgs.isByteStringSource = true;
        }

        // For UTF-8 and other charsets, use standard decoding
        return new String(bytes, offset, bytes.length - offset, charset);
    }

    private static Charset detectCharsetWithoutBOM(byte[] bytes) {
        if (bytes.length >= 2) {
            // Check for UTF-16LE pattern (ASCII chars would have 0x00 as second byte)
            boolean couldBeUTF16LE = true;
            boolean couldBeUTF16BE = true;
            int nullCount = 0;

            int lookSize = 1000;
            int zeroesSize = lookSize / 5;

            for (int i = 0; i < Math.min(bytes.length, lookSize); i++) {
                if (bytes[i] == 0) nullCount++;
            }

            // If we have a lot of null bytes, it might be UTF-16
            if (nullCount > bytes.length / 4) {
                // Try to determine byte order by looking for ASCII patterns
                for (int i = 0; i < Math.min(bytes.length - 1, zeroesSize); i += 2) {
                    byte b1 = bytes[i];
                    byte b2 = bytes[i + 1];

                    // Common ASCII characters in range 0x20-0x7E
                    if (b1 >= 0x20 && b1 <= 0x7E && b2 == 0) {
                        couldBeUTF16BE = false;
                    } else if (b2 >= 0x20 && b2 <= 0x7E && b1 == 0) {
                        couldBeUTF16LE = false;
                    }
                }

                if (couldBeUTF16LE && !couldBeUTF16BE) {
                    return StandardCharsets.UTF_16LE;
                } else if (couldBeUTF16BE && !couldBeUTF16LE) {
                    return StandardCharsets.UTF_16BE;
                }
            }
        }

        // Check if file contains non-ASCII bytes that aren't valid UTF-8.
        // Perl 5 without 'use utf8' treats source as Latin-1 (ISO-8859-1).
        // We use UTF-8 for valid UTF-8 files (most modern files), but fall back
        // to ISO-8859-1 for files with invalid UTF-8 sequences (legacy Latin-1 files).
        if (hasNonAscii(bytes) && !isValidUtf8(bytes)) {
            return StandardCharsets.ISO_8859_1;
        }

        // Default to UTF-8
        return StandardCharsets.UTF_8;
    }

    /**
     * Checks if the byte array contains any non-ASCII bytes (> 0x7F).
     */
    private static boolean hasNonAscii(byte[] bytes) {
        for (byte b : bytes) {
            if ((b & 0x80) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates that the byte array is valid UTF-8.
     * Uses Java's CharsetDecoder with strict error handling.
     */
    private static boolean isValidUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
