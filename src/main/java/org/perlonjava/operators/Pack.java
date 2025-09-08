package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Provides functionality to pack a list of scalars into a binary string
 * based on a specified template, similar to Perl's pack function.
 */
public class Pack {

    /**
     * Packs a list of RuntimeScalar objects into a binary string according to the specified template.
     *
     * @param args A RuntimeList containing the template string followed by the values to pack.
     * @return A RuntimeScalar representing the packed binary string.
     * @throws RuntimeException if there are not enough arguments or if an unsupported format character is encountered.
     */
    public static RuntimeScalar pack(RuntimeList args) {
        if (args.isEmpty()) {
            throw new PerlCompilerException("pack: not enough arguments");
        }

        RuntimeScalar templateScalar = args.getFirst();
        String template = templateScalar.toString();

        // Flatten the remaining arguments into a RuntimeArray
        List<RuntimeBase> remainingArgs = args.elements.subList(1, args.elements.size());
        RuntimeArray flattened = new RuntimeArray(remainingArgs.toArray(new RuntimeBase[0]));
        List<RuntimeScalar> values = flattened.elements;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int valueIndex = 0;

        // Track current mode - default is normal/character mode
        boolean byteMode = false;  // false = character mode (default), true = byte mode (after C0)
        boolean byteModeUsed = false;  // Track if byte mode was ever used

        // Track if 'U' was used in normal mode (not byte mode)
        boolean hasUnicodeInNormalMode = false;

        for (int i = 0; i < template.length(); i++) {
            char format = template.charAt(i);

            // Skip spaces
            if (Character.isWhitespace(format)) {
                continue;
            }

            // Check for mode modifiers C0 and U0
            if (format == 'C' && i + 1 < template.length() && template.charAt(i + 1) == '0') {
                byteMode = true;  // C0 switches to byte mode
                byteModeUsed = true;  // Mark that byte mode was used
                i++; // Skip the '0'
                continue;
            } else if (format == 'U' && i + 1 < template.length() && template.charAt(i + 1) == '0') {
                byteMode = false;  // U0 switches to normal mode
                i++; // Skip the '0'
                continue;
            }

            int count = 1;
            boolean hasStar = false;

            // Check for repeat count or '*'
            if (i + 1 < template.length()) {
                char nextChar = template.charAt(i + 1);
                if (Character.isDigit(nextChar)) {
                    int j = i + 1;
                    while (j < template.length() && Character.isDigit(template.charAt(j))) {
                        j++;
                    }
                    count = Integer.parseInt(template.substring(i + 1, j));
                    i = j - 1;
                } else if (nextChar == '*') {
                    hasStar = true;
                    count = values.size() - valueIndex; // Use all remaining values
                    i++; // consume the '*'
                }
            }

            if (format == 'b' || format == 'B') {
                if (valueIndex >= values.size()) {
                    throw new PerlCompilerException("pack: not enough arguments");
                }
                RuntimeScalar value = values.get(valueIndex++);
                String bitString = value.toString();
                if (hasStar) {
                    count = bitString.length(); // For bit strings with *, use the entire string length
                }
                PackHelper.writeBitString(output, bitString, count, format);
            } else if (format == 'h' || format == 'H') {
                if (valueIndex >= values.size()) {
                    throw new PerlCompilerException("pack: not enough arguments");
                }
                RuntimeScalar value = values.get(valueIndex++);
                String hexString = value.toString();
                if (hasStar) {
                    count = hexString.length(); // For hex strings with *, use the entire string length
                }
                PackHelper.writeHexString(output, hexString, count, format);
            } else if (format == 'u') {
                // Uuencode format - consumes one value
                if (valueIndex >= values.size()) {
                    throw new PerlCompilerException("pack: not enough arguments");
                }
                RuntimeScalar value = values.get(valueIndex++);
                String str = value.toString();
                PackHelper.writeUuencodedString(output, str);
            } else if (format == 'a' || format == 'A' || format == 'Z') {
                valueIndex = handleStringFormat(valueIndex, values, hasStar, format, count, byteMode, output);
            } else {
                // Numeric formats
                for (int j = 0; j < count; j++) {
                    if (valueIndex >= values.size()) {
                        throw new PerlCompilerException("pack: not enough arguments");
                    }

                    RuntimeScalar value = values.get(valueIndex++);

                    // Check for Inf/NaN values for integer formats
                    if (PackHelper.isIntegerFormat(format)) {
                        handleInfinity(value, format);
                    }

                    switch (format) {
                        case 'c':
                            // Signed char
                            int signedChar = value.getInt();
                            output.write(signedChar & 0xFF);
                            break;
                        case 'C':
                            // Unsigned char
                            int intValue = value.getInt();
                            output.write(intValue & 0xFF);
                            break;
                        case 's':
                            PackHelper.writeShortLittleEndian(output, value.getInt());
                            break;
                        case 'S':
                            PackHelper.writeShort(output, value.getInt());
                            break;
                        case 'l':
                            PackHelper.writeIntLittleEndian(output, (long) value.getDouble());
                            break;
                        case 'L', 'J':
                            PackHelper.writeLong(output, (long) value.getDouble());
                            break;
                        case 'i':
                        case 'I':
                            // Native integer (assume 32-bit little-endian)
                            PackHelper.writeIntLittleEndian(output, (long) value.getDouble());
                            break;
                        case 'N':
                            PackHelper.writeIntBigEndian(output, (long) value.getDouble());
                            break;
                        case 'V':
                            PackHelper.writeIntLittleEndian(output, (long) value.getDouble());
                            break;
                        case 'n':
                            PackHelper.writeShortBigEndian(output, value.getInt());
                            break;
                        case 'v':
                            PackHelper.writeShortLittleEndian(output, value.getInt());
                            break;
                        case 'w':
                            // BER compressed integer
                            PackHelper.writeBER(output, (long) value.getDouble());
                            break;
                        case 'W':
                            packW(value, output);
                            break;
                        case 'U':
                            hasUnicodeInNormalMode = packU(value, byteMode, hasUnicodeInNormalMode, output);
                            break;
                        case 'f':
                            PackHelper.writeFloat(output, (float) value.getDouble());
                            break;
                        case 'F':
                            // F is double-precision float in native format (8 bytes)
                            PackHelper.writeDouble(output, value.getDouble());
                            break;
                        case 'd':
                            PackHelper.writeDouble(output, value.getDouble());
                            break;
                        default:
                            throw new PerlCompilerException("pack: unsupported format character: " + format);
                    }
                }
            }
        }

        // Convert the byte array to a string
        byte[] bytes = output.toByteArray();

        // Return UTF-8 decoded string only if we never used byte mode AND used U in character mode
        if (!byteModeUsed && hasUnicodeInNormalMode) {
            // Pure character mode with U format - decode UTF-8
            return new RuntimeScalar(new String(bytes, StandardCharsets.UTF_8));
        } else {
            // Mixed mode or byte mode - return as byte string
            return new RuntimeScalar(bytes);
        }
    }

    private static int handleStringFormat(int valueIndex, List<RuntimeScalar> values, boolean hasStar, char format, int count, boolean byteMode, ByteArrayOutputStream output) {
        // String formats consume only one value
        if (valueIndex >= values.size()) {
            throw new PerlCompilerException("pack: not enough arguments");
        }
        RuntimeScalar value = values.get(valueIndex++);
        String str = value.toString();
        if (hasStar) {
            // For string formats with *, use the string length as count
            if (format == 'Z') {
                count = str.length() + 1; // Include space for null terminator
            } else {
                count = str.length();
            }
        }
        // In byte mode, we need to handle the string differently
        if (byteMode && format == 'a') {
            // In byte mode with 'a', preserve the string as-is
            PackHelper.writeString(output, str, count, format, byteMode);
        } else {
            PackHelper.writeString(output, str, count, format, false);
        }
        return valueIndex;
    }

    private static void handleInfinity(RuntimeScalar value, char format) {
        String strValue = value.toString().trim();
        if (strValue.equalsIgnoreCase("Inf") || strValue.equalsIgnoreCase("+Inf") || strValue.equalsIgnoreCase("Infinity")) {
            if (format == 'w') {
                throw new PerlCompilerException("Cannot compress Inf");
            } else {
                throw new PerlCompilerException("Cannot pack Inf");
            }
        } else if (strValue.equalsIgnoreCase("-Inf") || strValue.equalsIgnoreCase("-Infinity")) {
            if (format == 'w') {
                throw new PerlCompilerException("Cannot compress -Inf");
            } else {
                throw new PerlCompilerException("Cannot pack -Inf");
            }
        } else if (strValue.equalsIgnoreCase("NaN")) {
            if (format == 'w') {
                throw new PerlCompilerException("Cannot compress NaN");
            } else {
                throw new PerlCompilerException("Cannot pack NaN");
            }
        }
    }

    private static void packW(RuntimeScalar value, ByteArrayOutputStream output) {
        // Pack a Unicode code point as UTF-8 bytes
        int codePoint;
        String strValue = value.toString();
        if (!strValue.isEmpty() && !Character.isDigit(strValue.charAt(0))) {
            // If it's a character, get its code point
            codePoint = strValue.codePointAt(0);
        } else {
            // If it's a number, use it directly as code point
            codePoint = value.getInt();
        }

        if (Character.isValidCodePoint(codePoint)) {
            String unicodeChar = new String(Character.toChars(codePoint));
            byte[] utf8Bytes = unicodeChar.getBytes(StandardCharsets.UTF_8);
            try {
                output.write(utf8Bytes);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            throw new PerlCompilerException("pack: invalid Unicode code point: " + codePoint);
        }
    }

    private static boolean packU(RuntimeScalar value, boolean byteMode, boolean hasUnicodeInNormalMode, ByteArrayOutputStream output) {
        // Pack a Unicode character number as UTF-8
        int codePoint1;
        String strValue1 = value.toString();
        if (!strValue1.isEmpty() && !Character.isDigit(strValue1.charAt(0))) {
            // If it's a character, get its code point
            codePoint1 = strValue1.codePointAt(0);
        } else {
            // If it's a number, use it directly
            codePoint1 = value.getInt();
        }

        // Track if U is used in character mode (not byte mode)
        if (!byteMode) {
            hasUnicodeInNormalMode = true;
        }

        // U format always writes UTF-8 encoded bytes
        // The difference between modes is handled at the final string conversion
        if (Character.isValidCodePoint(codePoint1)) {
            String unicodeChar1 = new String(Character.toChars(codePoint1));
            byte[] utf8Bytes1 = unicodeChar1.getBytes(StandardCharsets.UTF_8);
            try {
                output.write(utf8Bytes1);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            throw new PerlCompilerException("pack: invalid Unicode code point: " + codePoint1);
        }
        return hasUnicodeInNormalMode;
    }
}
