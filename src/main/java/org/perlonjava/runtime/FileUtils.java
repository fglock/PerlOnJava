package org.perlonjava.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    public static String readFileWithEncodingDetection(Path filePath) throws IOException {
        byte[] bytes = Files.readAllBytes(filePath);
        return detectEncodingAndDecode(bytes);
    }

    /**
     * Detects the encoding of file content based on BOM and heuristics, then decodes it.
     *
     * @param bytes The file content as bytes
     * @return The decoded string content
     */
    private static String detectEncodingAndDecode(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }

        // Check for BOM (Byte Order Mark)
        if (bytes.length >= 3 && bytes[0] == (byte)0xEF && bytes[1] == (byte)0xBB && bytes[2] == (byte)0xBF) {
            // UTF-8 BOM
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        } else if (bytes.length >= 2 && bytes[0] == (byte)0xFE && bytes[1] == (byte)0xFF) {
            // UTF-16BE BOM
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        } else if (bytes.length >= 2 && bytes[0] == (byte)0xFF && bytes[1] == (byte)0xFE) {
            // UTF-16LE BOM
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        } else {
            // No BOM - try to detect UTF-16 without BOM using heuristics
            if (bytes.length >= 2) {
                // Check for UTF-16LE pattern (ASCII chars would have 0x00 as second byte)
                boolean couldBeUTF16LE = true;
                boolean couldBeUTF16BE = true;
                int nullCount = 0;

                for (int i = 0; i < Math.min(bytes.length, 100); i++) {
                    if (bytes[i] == 0) nullCount++;
                }

                // If we have a lot of null bytes, it might be UTF-16
                if (nullCount > bytes.length / 4) {
                    // Try to determine byte order by looking for ASCII patterns
                    for (int i = 0; i < Math.min(bytes.length - 1, 20); i += 2) {
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
                        return new String(bytes, StandardCharsets.UTF_16LE);
                    } else if (couldBeUTF16BE && !couldBeUTF16LE) {
                        return new String(bytes, StandardCharsets.UTF_16BE);
                    }
                }
            }

            // Default to UTF-8
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}