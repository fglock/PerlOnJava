package org.perlonjava.operators.pack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * PackBuffer is a specialized buffer for pack operations that can store both:
 * - Raw bytes (from binary formats like N, V, etc.)
 * - Unicode character codes (from W/U formats)
 * <p>
 * This allows proper UTF-8 flag handling when W format is mixed with binary formats.
 * When converted to a string with UTF-8 flag set, all values are interpreted as
 * Latin-1 characters (0x00-0xFF and Unicode > 255), matching Perl's utf8::upgrade behavior.
 */
public class PackBuffer {
    private final List<Integer> values = new ArrayList<>();
    private final List<Boolean> isCharacter = new ArrayList<>();

    /**
     * Write a raw byte value (from binary formats like N, V, s, etc.)
     */
    public void writeByte(int b) {
        values.add(b & 0xFF);
        isCharacter.add(false);
    }

    /**
     * Write multiple raw bytes
     */
    public void writeBytes(byte[] bytes) {
        for (byte b : bytes) {
            writeByte(b);
        }
    }

    /**
     * Write a single byte (ByteArrayOutputStream compatibility)
     */
    public void write(int b) {
        writeByte(b);
    }

    /**
     * Write bytes from array (ByteArrayOutputStream compatibility)
     */
    public void write(byte[] bytes, int offset, int length) {
        for (int i = 0; i < length; i++) {
            writeByte(bytes[offset + i]);
        }
    }

    /**
     * Write all bytes from array (ByteArrayOutputStream compatibility)
     */
    public void write(byte[] bytes) {
        writeBytes(bytes);
    }

    /**
     * Write a Unicode character code (from W/U formats)
     * For characters 0-255, stored as-is
     * For characters > 255, stored as the character code
     */
    public void writeCharacter(int codePoint) {
        values.add(codePoint);
        isCharacter.add(true);
    }

    /**
     * Convert to byte array (for BYTE_STRING - no UTF-8 flag)
     */
    public byte[] toByteArray() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < values.size(); i++) {
            int value = values.get(i);
            if (isCharacter.get(i) && value > 255) {
                // Character > 255 needs UTF-8 encoding
                String ch = new String(Character.toChars(value));
                try {
                    out.write(ch.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                out.write(value & 0xFF);
            }
        }
        return out.toByteArray();
    }

    /**
     * Convert to String (for STRING - UTF-8 flag set)
     * Interprets all values as Latin-1 characters, matching Perl's utf8::upgrade
     */
    public String toUpgradedString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            int value = values.get(i);
            // All values become characters: bytes 0-255 map to U+0000-U+00FF
            // Character codes > 255 are already Unicode characters
            if (value > 0x10FFFF) {
                value = value & 0x10FFFF; // Ensure valid Unicode range
            }
            sb.appendCodePoint(value);
        }
        return sb.toString();
    }

    /**
     * Get the size in bytes (for x[template] calculations)
     */
    public int size() {
        return values.size();
    }

    /**
     * Check if the buffer contains any Unicode characters (> 255).
     * This determines whether . and @ formats should work in character mode.
     */
    public boolean hasUnicodeCharacters() {
        for (int i = 0; i < values.size(); i++) {
            if (isCharacter.get(i) && values.get(i) > 255) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the size in characters (for UTF-8 strings with W format).
     * Same as size() since we store one value per character.
     */
    public int sizeInCharacters() {
        return values.size();
    }

    /**
     * Truncate the buffer to the specified character position.
     * For UTF-8 strings, this truncates at character boundaries.
     *
     * @param charPos The character position to truncate to
     */
    public void truncateToCharacter(int charPos) {
        if (charPos < 0) charPos = 0;
        if (charPos >= values.size()) return;
        
        while (values.size() > charPos) {
            values.remove(values.size() - 1);
            isCharacter.remove(isCharacter.size() - 1);
        }
    }

    /**
     * Reset the buffer (clear all data)
     * ByteArrayOutputStream compatibility method
     */
    public void reset() {
        values.clear();
        isCharacter.clear();
    }
}
