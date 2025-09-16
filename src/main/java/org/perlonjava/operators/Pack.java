package org.perlonjava.operators;

import org.perlonjava.operators.pack.PackHelper;
import org.perlonjava.runtime.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            System.err.println("DEBUG: main loop i=" + i + ", format='" + format + "' (code " + (int) format + ")");

            // Skip spaces
            if (Character.isWhitespace(format)) {
                System.err.println("DEBUG: skipping whitespace");
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

            // Handle parentheses for grouping
            if (format == '(') {
                // Find matching closing parenthesis
                int closePos = PackHelper.findMatchingParen(template, i);
                if (closePos == -1) {
                    throw new PerlCompilerException("pack: unmatched parenthesis in template");
                }

                // Extract group content
                String groupContent = template.substring(i + 1, closePos);
                System.err.println("DEBUG: found group at positions " + i + " to " + closePos + ", content: '" + groupContent + "'");

                // Check for modifiers and repeat count after the group
                int nextPos = closePos + 1;
                char groupEndian = ' ';
                int groupRepeatCount = 1;

                // Parse modifiers after ')'
                while (nextPos < template.length()) {
                    char nextChar = template.charAt(nextPos);
                    if (nextChar == '<' || nextChar == '>') {
                        if (groupEndian == ' ') {
                            groupEndian = nextChar;
                        }
                        nextPos++;
                    } else if (nextChar == '!') {
                        // Skip '!' for now
                        nextPos++;
                    } else if (nextChar == '[') {
                        // Parse repeat count in brackets [n]
                        int j = nextPos + 1;
                        while (j < template.length() && Character.isDigit(template.charAt(j))) {
                            j++;
                        }
                        if (j >= template.length() || template.charAt(j) != ']') {
                            throw new PerlCompilerException("No group ending character ']' found in template");
                        }
                        groupRepeatCount = Integer.parseInt(template.substring(nextPos + 1, j));
                        nextPos = j + 1; // Move past ']'
                        break;
                    } else if (Character.isDigit(nextChar)) {
                        // Parse repeat count
                        int j = nextPos;
                        while (j < template.length() && Character.isDigit(template.charAt(j))) {
                            j++;
                        }
                        groupRepeatCount = Integer.parseInt(template.substring(nextPos, j));
                        nextPos = j;
                        break;
                    } else if (nextChar == '*') {
                        groupRepeatCount = Integer.MAX_VALUE;
                        nextPos++;
                        break;
                    } else {
                        break;
                    }
                }

                // Check for conflicting endianness within the group
                if (groupEndian != ' ' && PackHelper.hasConflictingEndianness(groupContent, groupEndian)) {
                    throw new PerlCompilerException("Can't use '" + groupEndian + "' in a group with different byte-order in pack");
                }

                // Process the group content repeatedly
                for (int rep = 0; rep < groupRepeatCount; rep++) {
                    // Create a sub-packer for the group content
                    RuntimeList groupArgs = new RuntimeList();
                    groupArgs.add(new RuntimeScalar(groupContent));

                    // Collect values for this group iteration
                    int groupValueCount = PackHelper.countValuesNeeded(groupContent);
                    System.err.println("DEBUG: group '" + groupContent + "' needs " + groupValueCount + " values, valueIndex=" + valueIndex + ", remaining=" + (values.size() - valueIndex));

                    // Add more specific handling for * groups
                    if (groupRepeatCount == Integer.MAX_VALUE) {
                        // For *, we need to calculate how many values one iteration needs
                        // If countValuesNeeded returned MAX_VALUE, we have a problem
                        if (groupValueCount == Integer.MAX_VALUE) {
                            // This shouldn't happen for group content
                            System.err.println("ERROR: countValuesNeeded returned MAX_VALUE for group content: " + groupContent);
                            // Try to calculate it differently
                            // For now, just break
                            break;
                        }

                        // Check if we have enough values for at least one complete iteration
                        if (values.size() - valueIndex < groupValueCount) {
                            System.err.println("DEBUG: not enough values for complete group iteration, breaking");
                            break;
                        }
                    }

                    for (int v = 0; v < groupValueCount && valueIndex < values.size(); v++) {
                        groupArgs.add(values.get(valueIndex++));
                    }
                    System.err.println("DEBUG: collected " + (groupArgs.size() - 1) + " values for group");

                    // Recursively pack the group
                    RuntimeScalar groupResult = pack(groupArgs);
                    byte[] groupBytes = groupResult.toString().getBytes(StandardCharsets.ISO_8859_1);
                    output.write(groupBytes, 0, groupBytes.length);
                }

                // Move past the group and modifiers
                i = nextPos - 1; // -1 because loop will increment
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

            // Parse modifiers BEFORE parsing counts
            boolean bigEndian = false;

            // Keep checking for modifiers while we find them
            while (i + 1 < template.length()) {
                char modifier = template.charAt(i + 1);
                if (modifier == '<') {
                    i++; // consume the '<'
                    // System.err.println("DEBUG: found little-endian modifier");
                } else if (modifier == '>') {
                    bigEndian = true;
                    i++; // consume the '>'
                    // System.err.println("DEBUG: found big-endian modifier");
                } else if (modifier == '!') {
                    i++; // consume the '!'
                    // System.err.println("DEBUG: found native-size modifier");
                } else {
                    // Not a modifier, stop looking
                    break;
                }
            }

            // Check if this numeric format is part of a '/' construct BEFORE parsing count
            if (PackHelper.isNumericFormat(format) || format == 'Z') {
                int slashPos = PackHelper.checkForSlashConstruct(template, i);
                if (slashPos != -1) {
                    System.err.println("DEBUG: handling " + format + "/ construct at position " + i);

                    // Skip whitespace after '/'
                    int stringPos = slashPos + 1;
                    while (stringPos < template.length() && Character.isWhitespace(template.charAt(stringPos))) {
                        stringPos++;
                    }

                    if (stringPos >= template.length()) {
                        throw new PerlCompilerException("Code missing after '/'");
                    }

                    char stringFormat = template.charAt(stringPos);
                    System.err.println("DEBUG: string format after '/' is '" + stringFormat + "'");

                    // Validate string format
                    if (stringFormat != 'a' && stringFormat != 'A' && stringFormat != 'Z') {
                        throw new PerlCompilerException("'/' must be followed by a string type");
                    }

                    // Check for count after string format
                    int stringCount = -1; // -1 means use full string
                    int endPos = stringPos;

                    if (stringPos + 1 < template.length()) {
                        if (template.charAt(stringPos + 1) == '*') {
                            endPos = stringPos + 1;
                        } else if (Character.isDigit(template.charAt(stringPos + 1))) {
                            int j = stringPos + 1;
                            while (j < template.length() && Character.isDigit(template.charAt(j))) {
                                j++;
                            }
                            stringCount = Integer.parseInt(template.substring(stringPos + 1, j));
                            endPos = j - 1;
                        }
                    }

                    // Now get the string value
                    if (valueIndex >= values.size()) {
                        System.err.println("DEBUG: pack N/ needs value at index " + valueIndex + " but only have " + values.size() + " values");
                        throw new PerlCompilerException("pack: not enough arguments");
                    }

                    RuntimeScalar strValue = values.get(valueIndex++);
                    String str = strValue.toString();
                    System.err.println("DEBUG: pack N/ consuming string '" + str + "'");

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
                        byte padByte = (stringFormat == 'A') ? (byte) ' ' : (byte) 0;
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
                    System.err.println("DEBUG: packing length " + lengthToWrite + " with format '" + format + "'");
                    switch (format) {
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
                        case 's':
                            if (bigEndian) {
                                PackHelper.writeShortBigEndian(output, lengthToWrite);
                            } else {
                                PackHelper.writeShortLittleEndian(output, lengthToWrite);
                            }
                            break;
                        case 'S':
                            if (bigEndian) {
                                PackHelper.writeShortBigEndian(output, lengthToWrite);
                            } else {
                                PackHelper.writeShort(output, lengthToWrite);
                            }
                            break;
                        case 'i':
                        case 'I':
                        case 'l':
                        case 'L':
                            if (bigEndian) {
                                PackHelper.writeIntBigEndian(output, lengthToWrite);
                            } else {
                                PackHelper.writeIntLittleEndian(output, lengthToWrite);
                            }
                            break;
                        case 'Z':
                            // For Z*/, encode length as null-terminated decimal string
                            String lengthStr = String.valueOf(lengthToWrite);
                            byte[] lengthBytes = lengthStr.getBytes(StandardCharsets.US_ASCII);
                            output.write(lengthBytes, 0, lengthBytes.length);
                            output.write(0); // null terminator
                            break;
                        default:
                            throw new PerlCompilerException("Invalid length type '" + format + "' for '/'");
                    }

                    // Write the string data
                    output.write(dataToWrite, 0, dataToWrite.length);
                    if (stringFormat == 'Z' && stringCount < 0) {
                        output.write(0); // null terminator
                    }

                    // Update position to end of the N/X construct
                    i = endPos;
                    continue;
                }
            }

            int count = 1;
            boolean hasStar = false;

            // Check for repeat count or '*' AFTER endianness modifier
            if (i + 1 < template.length()) {
                char nextChar = template.charAt(i + 1);
                if (nextChar == '[') {
                    // Parse repeat count in brackets [n]
                    int j = i + 2;
                    while (j < template.length() && Character.isDigit(template.charAt(j))) {
                        j++;
                    }
                    if (j >= template.length() || template.charAt(j) != ']') {
                        throw new PerlCompilerException("No group ending character ']' found in template");
                    }
                    String countStr = template.substring(i + 2, j);
                    count = Integer.parseInt(countStr);
                    i = j; // Position at ']'
                } else if (Character.isDigit(nextChar)) {
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
                valueIndex = PackHelper.handleStringFormat(valueIndex, values, hasStar, format, count, byteMode, output);
            } else if (format == 'x') {
                for (int j = 0; j < count; j++) {
                    output.write(0);
                }
            } else if (format == '/') {
                System.err.println("DEBUG: entering '/' handler, i=" + i);

                // This should only happen if '/' appears without a preceding numeric format
                // which is an error
                throw new PerlCompilerException("Invalid type '/'");
            } else if (format == '@') {
                // @ is used for absolute positioning
                // @n means null-fill or truncate to position n
                int currentPosition = output.size();

                if (count > currentPosition) {
                    // Pad with nulls to reach target position
                    for (int k = currentPosition; k < count; k++) {
                        output.write(0);
                    }
                } else if (count < currentPosition) {
                    // Truncate to target position
                    byte[] truncated = new byte[count];
                    System.arraycopy(output.toByteArray(), 0, truncated, 0, count);
                    output.reset();
                    output.write(truncated, 0, count);
                }
            } else {
                // Numeric formats
                for (int j = 0; j < count; j++) {
                    if (valueIndex >= values.size()) {
                        throw new PerlCompilerException("pack: not enough arguments");
                    }

                    RuntimeScalar value = values.get(valueIndex++);

                    // Check for Inf/NaN values for integer formats
                    if (PackHelper.isIntegerFormat(format)) {
                        PackHelper.handleInfinity(value, format);
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
                            PackHelper.packW(value, output);
                            break;
                        case 'U':
                            hasUnicodeInNormalMode = PackHelper.packU(value, byteMode, hasUnicodeInNormalMode, output);
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
}