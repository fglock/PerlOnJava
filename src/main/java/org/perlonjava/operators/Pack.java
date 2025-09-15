package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Provides functionality to pack a list of scalars into a binary string
 * based on a specified template, similar to Perl's pack function.
 */
public class Pack {
    // Temporary storage for pointer simulation
    private static final Map<Integer, String> pointerMap = new HashMap<>();

    // Add getter for unpack to use
    public static String getPointerString(int hashCode) {
        return pointerMap.get(hashCode);
    }

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

        // System.err.println("DEBUG: pack has " + values.size() + " values");
        for (int idx = 0; idx < values.size(); idx++) {
            // System.err.println("DEBUG: value[" + idx + "] = '" + values.get(idx).toString() + "'");
        }

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

            // Skip comments
            if (format == '#') {
                // Skip to end of line or end of template
                while (i + 1 < template.length() && template.charAt(i + 1) != '\n') {
                    i++;
                }
                continue;
            }

            // Skip spaces
            if (Character.isWhitespace(format)) {
                continue;
            }

            // Skip comments
            if (format == '#') {
                // Skip to end of line or end of template
                while (i + 1 < template.length() && template.charAt(i + 1) != '\n') {
                    i++;
                }
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

            // Check if this is a numeric format followed by '/' - skip it entirely
            if (isNumericFormat(format) && i + 1 < template.length() && template.charAt(i + 1) == '/') {
                // System.err.println("DEBUG: skipping format '" + format + "' because it's followed by '/', valueIndex=" + valueIndex);
                continue;
            }

            // Parse modifiers BEFORE parsing counts
            boolean bigEndian = false;
            boolean littleEndian = false;
            boolean nativeSize = false;

            // Keep checking for modifiers while we find them
            while (i + 1 < template.length()) {
                char modifier = template.charAt(i + 1);
                if (modifier == '<') {
                    littleEndian = true;
                    i++; // consume the '<'
                    // System.err.println("DEBUG: found little-endian modifier");
                } else if (modifier == '>') {
                    bigEndian = true;
                    i++; // consume the '>'
                    // System.err.println("DEBUG: found big-endian modifier");
                } else if (modifier == '!') {
                    nativeSize = true;
                    i++; // consume the '!'
                    // System.err.println("DEBUG: found native-size modifier");
                } else {
                    // Not a modifier, stop looking
                    break;
                }
            }

            int count = 1;
            boolean hasStar = false;

            // Check for repeat count or '*' AFTER endianness modifier
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
            } else if (format == 'x') {
                for (int j = 0; j < count; j++) {
                    output.write(0);
                }
            } else if (format == '/') {
                // System.err.println("DEBUG: entering '/' handler, valueIndex=" + valueIndex);
                // '/' must follow a numeric type
                if (i == 0) {
                    throw new PerlCompilerException("Invalid type '/'");
                }

                // Find the numeric format that precedes '/'
                // Need to look back, skipping any modifiers
                int numericPos = i - 1;
                while (numericPos > 0 && (template.charAt(numericPos) == '<' ||
                                         template.charAt(numericPos) == '>' ||
                                         template.charAt(numericPos) == '!')) {
                    numericPos--;
                }

                char lengthFormat = template.charAt(numericPos);
                if (!isNumericFormat(lengthFormat)) {
                    throw new PerlCompilerException("'/' must follow a numeric type");
                }

                // Get the string format that follows '/'
                if (i + 1 >= template.length()) {
                    throw new PerlCompilerException("'/' must be followed by a string type");
                }

                i++; // move to string format
                char stringFormat = template.charAt(i);
                if (stringFormat != 'a' && stringFormat != 'A' && stringFormat != 'Z') {
                    throw new PerlCompilerException("'/' must be followed by a string type");
                }

                // Parse count for string format
                int stringCount = -1; // -1 means use full string
                if (i + 1 < template.length() && template.charAt(i + 1) == '*') {
                    i++; // consume the '*'
                } else if (i + 1 < template.length() && Character.isDigit(template.charAt(i + 1))) {
                    int endPos = i + 1;
                    while (endPos < template.length() && Character.isDigit(template.charAt(endPos))) {
                        endPos++;
                    }
                    stringCount = Integer.parseInt(template.substring(i + 1, endPos));
                    i = endPos - 1; // position at last digit
                }

                // Get the string value
                if (valueIndex >= values.size()) {
                    // System.err.println("DEBUG: pack '/' needs value at index " + valueIndex + " but only have " + values.size() + " values");
                    throw new PerlCompilerException("pack: not enough arguments");
                }
                // System.err.println("DEBUG: pack '/' consuming value[" + valueIndex + "] = '" + values.get(valueIndex).toString() + "'");
                RuntimeScalar strValue = values.get(valueIndex++);
                String str = strValue.toString();

                // Determine what to pack
                byte[] dataToWrite;
                int lengthToWrite;

                if (stringCount >= 0) {
                    // Specific count requested
                    byte[] strBytes = str.getBytes(StandardCharsets.UTF_8);
                    int actualCount = Math.min(stringCount, strBytes.length);
                    dataToWrite = new byte[stringCount];
                    System.arraycopy(strBytes, 0, dataToWrite, 0, actualCount);
                    // Pad with nulls or spaces depending on format
                    byte padByte = (stringFormat == 'A') ? (byte)' ' : (byte)0;
                    for (int k = actualCount; k < stringCount; k++) {
                        dataToWrite[k] = padByte;
                    }
                    lengthToWrite = stringCount;
                } else {
                    // Use full string
                    dataToWrite = str.getBytes(StandardCharsets.UTF_8);
                    lengthToWrite = dataToWrite.length;
                    if (stringFormat == 'Z') {
                        lengthToWrite++; // Include null terminator in count
                    }
                }

                // Pack the length using the numeric format
                switch (lengthFormat) {
                    case 'n':
                        PackHelper.writeShortBigEndian(output, lengthToWrite);
                        break;
                    case 'N':
                        PackHelper.writeIntBigEndian(output, lengthToWrite);
                        break;
                    case 'v':
                        PackHelper.writeShortLittleEndian(output, lengthToWrite);
                        break;
                    case 'V':
                        PackHelper.writeIntLittleEndian(output, lengthToWrite);
                        break;
                    case 'w':
                        PackHelper.writeBER(output, lengthToWrite);
                        break;
                    case 'C':
                        output.write(lengthToWrite & 0xFF);
                        break;
                    case 'Z':
                        output.write(lengthToWrite & 0xFF);
                        break;
                    default:
                        throw new PerlCompilerException("Invalid length type '" + lengthFormat + "' for '/'");
                }

                // Write the string data
                output.write(dataToWrite, 0, dataToWrite.length);
                if (stringFormat == 'Z' && stringCount < 0) {
                    output.write(0); // null terminator
                }
            } else {
                // Check if this is a numeric format followed by '/' - skip it
                if (isNumericFormat(format) && i + 1 < template.length() && template.charAt(i + 1) == '/') {
                    // System.err.println("DEBUG: skipping format '" + format + "' because it's followed by '/', valueIndex=" + valueIndex);
                    continue;
                }

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
                            // Signed short - use endianness if specified
                            if (bigEndian) {
                                PackHelper.writeShortBigEndian(output, value.getInt());
                            } else {
                                PackHelper.writeShortLittleEndian(output, value.getInt());
                            }
                            break;
                        case 'S':
                            // Unsigned short - use endianness if specified
                            if (bigEndian) {
                                PackHelper.writeShortBigEndian(output, value.getInt());
                            } else {
                                PackHelper.writeShort(output, value.getInt());
                            }
                            break;
                        case 'l':
                            PackHelper.writeIntLittleEndian(output, (long) value.getDouble());
                            break;
                        case 'L':
                        case 'J':
                            PackHelper.writeLong(output, (long) value.getDouble());
                            break;
                        case 'i':
                        case 'I':
                            // Native integer (assume 32-bit little-endian)
                            PackHelper.writeIntLittleEndian(output, (long) value.getDouble());
                            break;
                        case 'n':
                            // Network short (always big-endian)
                            PackHelper.writeShortBigEndian(output, value.getInt());
                            break;
                        case 'N':
                            // Network long (always big-endian)
                            PackHelper.writeIntBigEndian(output, (long) value.getDouble());
                            break;
                        case 'v':
                            // VAX short (always little-endian)
                            PackHelper.writeShortLittleEndian(output, value.getInt());
                            break;
                        case 'V':
                            // VAX long (always little-endian)
                            PackHelper.writeIntLittleEndian(output, (long) value.getDouble());
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
                        case 'p':
                            // Pack a pointer - simulate using hashCode
                            int ptr = 0;
                            // Check if value is defined (not undef)
                            if (value.getDefinedBoolean()) {
                                String str = value.toString();
                                ptr = str.hashCode();
                                pointerMap.put(ptr, str);
                                // System.err.println("DEBUG: pack 'p' storing '" + str + "' with hash " + ptr);
                            }

                            // Use the already-parsed endianness
                            if (bigEndian) {
                                PackHelper.writeIntBigEndian(output, ptr);
                            } else {
                                PackHelper.writeIntLittleEndian(output, ptr);
                            }
                            break;
                        default:
                            throw new PerlCompilerException("pack: unsupported format character: " + format);
                    }
                }
            }
            // System.err.println("DEBUG: end of loop iteration, i=" + i + ", valueIndex=" + valueIndex);
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

    private static boolean isNumericFormat(char format) {
        switch (format) {
            case 'c':
            case 'C':
            case 's':
            case 'S':
            case 'l':
            case 'L':
            case 'i':
            case 'I':
            case 'n':
            case 'N':
            case 'v':
            case 'V':
            case 'w':
            case 'W':
            case 'q':
            case 'Q':
            case 'j':
            case 'J':
            case 'Z':  // Z can be used as a count
                return true;
            default:
                return false;
        }
    }
}
