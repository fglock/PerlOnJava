package org.perlonjava.operators.pack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

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
    private final Stack<Integer> groupStartStack = new Stack<>();
    private int currentPosition = 0;

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
     * Push a group start position onto the stack for relative positioning
     */
    public void pushGroupStart(int position) {
        groupStartStack.push(position);
    }

    /**
     * Pop a group start position from the stack
     */
    public void popGroupStart() {
        if (!groupStartStack.isEmpty()) {
            groupStartStack.pop();
        }
    }

    /**
     * Get the group start position at the specified depth (1 = innermost)
     */
    public int getGroupStart(int depth) {
        if (groupStartStack.isEmpty()) {
            return 0;
        }
        int index = groupStartStack.size() - depth;
        if (index < 0) {
            return 0;
        }
        return groupStartStack.get(index);
    }

    /**
     * Get the byte offset of a character index
     */
    public int byteOffsetOfIndex(int index) {
        int byteOffset = 0;
        for (int i = 0; i < Math.min(index, values.size()); i++) {
            int value = values.get(i);
            if (isCharacter.get(i) && value > 127) {
                // UTF-8 encoding for characters > 127
                if (value <= 0x7FF) {
                    byteOffset += 2;
                } else if (value <= 0xFFFF) {
                    byteOffset += 3;
                } else {
                    byteOffset += 4;
                }
            } else {
                byteOffset += 1;
            }
        }
        return byteOffset;
    }

    /**
     * Move to a specific character index
     */
    public void moveToIndex(int index) {
        currentPosition = index;
        // Pad with nulls if needed
        while (values.size() < index) {
            writeByte(0);
        }
        // Truncate if moving backwards
        while (values.size() > index) {
            values.remove(values.size() - 1);
            isCharacter.remove(isCharacter.size() - 1);
        }
    }

    /**
     * Move to a specific byte position
     */
    public void moveToBytePosition(int bytePos) {
        // Find the character index that corresponds to this byte position
        int byteOffset = 0;
        int charIndex = 0;
        while (charIndex < values.size() && byteOffset < bytePos) {
            int value = values.get(charIndex);
            if (isCharacter.get(charIndex) && value > 127) {
                if (value <= 0x7FF) {
                    byteOffset += 2;
                } else if (value <= 0xFFFF) {
                    byteOffset += 3;
                } else {
                    byteOffset += 4;
                }
            } else {
                byteOffset += 1;
            }
            charIndex++;
        }
        // Pad with nulls if we haven't reached the byte position
        while (byteOffset < bytePos) {
            writeByte(0);
            byteOffset++;
        }
        currentPosition = charIndex;
    }

    /**
     * Get the current byte size (accounting for UTF-8 encoding)
     */
    public int byteSize() {
        return byteOffsetOfIndex(values.size());
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
