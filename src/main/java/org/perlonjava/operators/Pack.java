package org.perlonjava.operators;

import org.perlonjava.operators.pack.*;
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

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int valueIndex = 0;

        // Track current mode - default is normal/character mode
        boolean byteMode = false;  // false = character mode (default), true = byte mode (after C0)
        boolean byteModeUsed = false;  // Track if byte mode was ever used

        // Track if 'U' was used in normal mode (not byte mode)
        boolean hasUnicodeInNormalMode = false;

        // Stack to track byte order for nested groups
        java.util.Stack<Character> groupEndianStack = new java.util.Stack<>();
        groupEndianStack.push(' '); // Default: no specific endianness

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
                i = skipComment(template, i);
                continue;
            }

            // NEW: Handle commas (skip with warning)
            if (format == ',') {
                System.err.println("WARNING: Invalid type ',' in pack");
                // In Perl, this would use warn() but continue execution
                continue;
            }

            // Handle parentheses for grouping
            if (format == '(') {
                i = handleGroup(template, i, values, output, valueIndex);
                valueIndex = getValueIndexAfterGroup(template, i, values, valueIndex);
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
            ParsedModifiers modifiers = PackParser.parseModifiers(template, i);

            // Check if this numeric format is part of a '/' construct
            // Check from current position i (not after modifiers) to catch S / A* with spaces
            if (PackHelper.isNumericFormat(format) || format == 'Z' || format == 'A' || format == 'a') {
                int slashPos = PackHelper.checkForSlashConstruct(template, i);
                if (slashPos != -1) {
                    System.err.println("DEBUG: Detected slash construct for format '" + format + "' at position " + i);
                    i = handleSlashConstruct(template, i, slashPos, format, values, valueIndex, output, modifiers);
                    valueIndex++;
                    continue;
                }
            }

            // Update position after checking for slash
            i = modifiers.endPosition;

            // Parse repeat count
            ParsedCount parsedCount = PackParser.parseRepeatCount(template, i);
            i = parsedCount.endPosition;
            int count = parsedCount.count;
            boolean hasStar = parsedCount.hasStar;

            if (hasStar && count == 1) {
                count = values.size() - valueIndex; // Use all remaining values
            }

            // Handle the format
            switch (format) {
                case 'b':
                case 'B':
                    valueIndex = handleBitString(values, valueIndex, format, count, hasStar, output);
                    break;
                case 'h':
                case 'H':
                    valueIndex = handleHexString(values, valueIndex, format, count, hasStar, output);
                    break;
                case 'u':
                    valueIndex = handleUuencode(values, valueIndex, output);
                    break;
                case 'a':
                case 'A':
                case 'Z':
                    valueIndex = PackHelper.handleStringFormat(valueIndex, values, hasStar, format, count, byteMode, output);
                    break;
                case 'x':
                    handleNullPadding(count, output);
                    break;
                case 'X':
                    handleBackup(count, output);
                    break;
                case '/':
                    // In Perl, '/' can appear after any format, but requires code after it
                    // Skip whitespace after '/'
                    int nextPos = i + 1;
                    while (nextPos < template.length() && Character.isWhitespace(template.charAt(nextPos))) {
                        nextPos++;
                    }

                    if (nextPos >= template.length()) {
                        throw new PerlCompilerException("Code missing after '/'");
                    } else {
                        throw new PerlCompilerException("'/' must follow a numeric type in pack");
                    }
                case '@':
                    handleAbsolutePosition(count, output);
                    break;
                case 'p':
                    valueIndex = handlePointer(values, valueIndex, count, modifiers, output);
                    break;
                case 'P':
                    valueIndex = handlePointer(values, valueIndex, count, modifiers, output);
                    break;
                case 'U':
                    hasUnicodeInNormalMode = handleUnicode(values, valueIndex, count, byteMode, hasUnicodeInNormalMode, output);
                    valueIndex += count;
                    break;
                case 'W':
                    valueIndex = handleWideCharacter(values, valueIndex, count, output);
                    break;
                default:
                    valueIndex = handleNumericFormat(values, valueIndex, count, format, modifiers, output);
                    break;
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

    private static int skipComment(String template, int position) {
        while (position + 1 < template.length() && template.charAt(position + 1) != '\n') {
            position++;
        }
        return position;
    }

    private static int handleGroup(String template, int openPos, List<RuntimeScalar> values,
                                   ByteArrayOutputStream output, int valueIndex) {
        // Find matching closing parenthesis
        int closePos = PackHelper.findMatchingParen(template, openPos);
        if (closePos == -1) {
            throw new PerlCompilerException("pack: unmatched parenthesis in template");
        }

        // Extract group content
        String groupContent = template.substring(openPos + 1, closePos);
        System.err.println("DEBUG: found group at positions " + openPos + " to " + closePos + ", content: '" + groupContent + "'");

        // Parse group modifiers and repeat count
        GroupInfo groupInfo = PackParser.parseGroupInfo(template, closePos);

        // Check for conflicting endianness within the group
        if (groupInfo.endian != ' ' && PackHelper.hasConflictingEndianness(groupContent, groupInfo.endian)) {
            throw new PerlCompilerException("Can't use '" + groupInfo.endian + "' in a group with different byte-order in pack");
        }

        // Process the group content repeatedly
        for (int rep = 0; rep < groupInfo.repeatCount; rep++) {
            // Create a sub-packer for the group content
            RuntimeList groupArgs = new RuntimeList();
            groupArgs.add(new RuntimeScalar(groupContent));

            // Collect values for this group iteration
            int groupValueCount = PackHelper.countValuesNeeded(groupContent);
            System.err.println("DEBUG: group '" + groupContent + "' needs " + groupValueCount + " values, valueIndex=" + valueIndex + ", remaining=" + (values.size() - valueIndex));

            // Handle * groups
            if (groupInfo.repeatCount == Integer.MAX_VALUE) {
                if (groupValueCount == Integer.MAX_VALUE || values.size() - valueIndex < groupValueCount) {
                    break;
                }
            }

            for (int v = 0; v < groupValueCount && valueIndex < values.size(); v++) {
                groupArgs.add(values.get(valueIndex++));
            }

            // Recursively pack the group
            RuntimeScalar groupResult = pack(groupArgs);
            byte[] groupBytes = groupResult.toString().getBytes(StandardCharsets.ISO_8859_1);
            output.write(groupBytes, 0, groupBytes.length);
        }

        return groupInfo.endPosition - 1; // -1 because loop will increment
    }

    private static int getValueIndexAfterGroup(String template, int groupEndPos, List<RuntimeScalar> values, int currentValueIndex) {
        // Find the group start
        int depth = 1;
        int groupStart = groupEndPos;
        while (groupStart >= 0 && depth > 0) {
            if (template.charAt(groupStart) == ')') depth++;
            else if (template.charAt(groupStart) == '(') depth--;
            groupStart--;
        }
        groupStart++; // Adjust to point to '('

        String groupContent = template.substring(groupStart + 1, groupEndPos);
        GroupInfo groupInfo = PackParser.parseGroupInfo(template, groupEndPos);

        int valuesPerIteration = PackHelper.countValuesNeeded(groupContent);
        if (valuesPerIteration == Integer.MAX_VALUE || groupInfo.repeatCount == Integer.MAX_VALUE) {
            // For *, consume as many complete iterations as possible
            while (currentValueIndex + valuesPerIteration <= values.size()) {
                currentValueIndex += valuesPerIteration;
            }
        } else {
            currentValueIndex += valuesPerIteration * groupInfo.repeatCount;
        }

        return currentValueIndex;
    }

    private static int handleSlashConstruct(String template, int position, int slashPos, char format,
                                            List<RuntimeScalar> values, int valueIndex,
                                            ByteArrayOutputStream output, ParsedModifiers modifiers) {
        System.err.println("DEBUG: handling " + format + "/ construct at position " + position);

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

        // Parse string count
        ParsedCount stringCountInfo = PackParser.parseRepeatCount(template, stringPos);
        int stringCount = stringCountInfo.hasStar ? -1 : stringCountInfo.count;
        int endPos = stringCountInfo.endPosition;

        // Get the string value
        if (valueIndex >= values.size()) {
            throw new PerlCompilerException("pack: not enough arguments");
        }

        RuntimeScalar strValue = values.get(valueIndex);
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
        packLength(output, format, lengthToWrite, modifiers);

        // Write the string data
        output.write(dataToWrite, 0, dataToWrite.length);
        if (stringFormat == 'Z' && stringCount < 0) {
            output.write(0); // null terminator
        }

        return endPos;
    }

    private static void packLength(ByteArrayOutputStream output, char format, int length, ParsedModifiers modifiers) {
        System.err.println("DEBUG: packing length " + length + " with format '" + format + "'");

        switch (format) {
            case 'A':
                // For A format as length, pack as ASCII decimal string with spaces
                String lengthStrA = String.valueOf(length);
                byte[] lengthBytesA = lengthStrA.getBytes(StandardCharsets.US_ASCII);
                output.write(lengthBytesA, 0, lengthBytesA.length);
                // Pad with spaces to make it fixed width if needed
                break;
            case 'a':
                // For a format as length, pack as ASCII decimal string with nulls
                String lengthStrLower = String.valueOf(length);
                byte[] lengthBytesLower = lengthStrLower.getBytes(StandardCharsets.US_ASCII);
                output.write(lengthBytesLower, 0, lengthBytesLower.length);
                break;
            case 'n':
                PackWriter.writeShortBigEndian(output, length);
                break;
            case 'N':
                PackWriter.writeIntBigEndian(output, length);
                break;
            case 'v':
                PackWriter.writeShortLittleEndian(output, length);
                break;
            case 'V':
                PackWriter.writeIntLittleEndian(output, length);
                break;
            case 'w':
                PackWriter.writeBER(output, length);
                break;
            case 'C':
                output.write(length & 0xFF);
                break;
            case 's':
                if (modifiers.bigEndian) {
                    PackWriter.writeShortBigEndian(output, length);
                } else {
                    PackWriter.writeShortLittleEndian(output, length);
                }
                break;
            case 'S':
                if (modifiers.bigEndian) {
                    PackWriter.writeShortBigEndian(output, length);
                } else {
                    PackWriter.writeShort(output, length);
                }
                break;
            case 'i':
            case 'I':
            case 'l':
            case 'L':
                if (modifiers.bigEndian) {
                    PackWriter.writeIntBigEndian(output, length);
                } else {
                    PackWriter.writeIntLittleEndian(output, length);
                }
                break;
            case 'Z':
                // For Z*/, encode length as null-terminated decimal string
                String lengthStr = String.valueOf(length);
                byte[] lengthBytes = lengthStr.getBytes(StandardCharsets.US_ASCII);
                output.write(lengthBytes, 0, lengthBytes.length);
                output.write(0); // null terminator
                break;
            default:
                throw new PerlCompilerException("Invalid length type '" + format + "' for '/'");
        }
    }

    private static int handleBitString(List<RuntimeScalar> values, int valueIndex, char format,
                                       int count, boolean hasStar, ByteArrayOutputStream output) {
        RuntimeScalar value;
        if (valueIndex >= values.size()) {
            // If no more arguments, use empty string as per Perl behavior
            value = new RuntimeScalar("");
        } else {
            value = values.get(valueIndex);
            valueIndex++;
        }

        String bitString = value.toString();
        if (hasStar) {
            count = bitString.length();
        }
        PackWriter.writeBitString(output, bitString, count, format);
        return valueIndex;
    }

    private static int handleHexString(List<RuntimeScalar> values, int valueIndex, char format,
                                       int count, boolean hasStar, ByteArrayOutputStream output) {
        RuntimeScalar value;
        if (valueIndex >= values.size()) {
            // If no more arguments, use empty string as per Perl behavior
            value = new RuntimeScalar("");
        } else {
            value = values.get(valueIndex);
            valueIndex++;
        }

        String hexString = value.toString();
        if (hasStar) {
            count = hexString.length();
        }
        PackWriter.writeHexString(output, hexString, count, format);
        return valueIndex;
    }

    private static int handleUuencode(List<RuntimeScalar> values, int valueIndex, ByteArrayOutputStream output) {
        RuntimeScalar value;
        if (valueIndex >= values.size()) {
            // If no more arguments, use empty string as per Perl behavior
            value = new RuntimeScalar("");
        } else {
            value = values.get(valueIndex);
            valueIndex++;
        }
        String str = value.toString();
        PackWriter.writeUuencodedString(output, str);
        return valueIndex;
    }

    private static void handleNullPadding(int count, ByteArrayOutputStream output) {
        for (int j = 0; j < count; j++) {
            output.write(0);
        }
    }

    private static void handleAbsolutePosition(int targetPosition, ByteArrayOutputStream output) {
        int currentPosition = output.size();

        if (targetPosition > currentPosition) {
            // Pad with nulls to reach target position
            for (int k = currentPosition; k < targetPosition; k++) {
                output.write(0);
            }
        } else if (targetPosition < currentPosition) {
            // Truncate to target position
            byte[] truncated = new byte[targetPosition];
            System.arraycopy(output.toByteArray(), 0, truncated, 0, targetPosition);
            output.reset();
            output.write(truncated, 0, targetPosition);
        }
    }

    private static void handleBackup(int count, ByteArrayOutputStream output) {
        System.err.println("DEBUG: handleBackup called with count=" + count + ", current size=" + output.size());
        int currentSize = output.size();

        if (count > currentSize) {
            throw new PerlCompilerException("'X' outside of string in pack");
        }

        int newSize = currentSize - count;

        if (newSize < currentSize) {
            // Truncate the output by backing up
            byte[] currentData = output.toByteArray();
            output.reset();
            if (newSize > 0) {
                output.write(currentData, 0, newSize);
            }
        }
        System.err.println("DEBUG: handleBackup finished, new size=" + output.size());
    }

    private static int handlePointer(List<RuntimeScalar> values, int valueIndex, int count,
                                     ParsedModifiers modifiers, ByteArrayOutputStream output) {
        for (int j = 0; j < count; j++) {
            RuntimeScalar value;
            if (valueIndex >= values.size()) {
                // If no more arguments, use empty string as per Perl behavior
                value = new RuntimeScalar("");
            } else {
                value = values.get(valueIndex);
                valueIndex++;
            }

            int ptr = 0;

            // Check if value is defined (not undef)
            if (value.getDefinedBoolean()) {
                String str = value.toString();
                ptr = str.hashCode();
                pointerMap.put(ptr, str);
            }

            // Use the already-parsed endianness
            if (modifiers.bigEndian) {
                PackWriter.writeIntBigEndian(output, ptr);
            } else {
                PackWriter.writeIntLittleEndian(output, ptr);
            }
        }
        return valueIndex;
    }

    private static boolean handleUnicode(List<RuntimeScalar> values, int valueIndex, int count,
                                         boolean byteMode, boolean hasUnicodeInNormalMode,
                                         ByteArrayOutputStream output) {
        for (int j = 0; j < count; j++) {
            RuntimeScalar value;
            if (valueIndex + j >= values.size()) {
                // If no more arguments, use 0 as per Perl behavior
                value = new RuntimeScalar(0);
            } else {
                value = values.get(valueIndex + j);
            }
            hasUnicodeInNormalMode = PackHelper.packU(value, byteMode, hasUnicodeInNormalMode, output);
        }
        return hasUnicodeInNormalMode;
    }

    private static int handleWideCharacter(List<RuntimeScalar> values, int valueIndex, int count,
                                           ByteArrayOutputStream output) {
        for (int j = 0; j < count; j++) {
            RuntimeScalar value;
            if (valueIndex >= values.size()) {
                // If no more arguments, use 0 as per Perl behavior (empty string converts to 0)
                value = new RuntimeScalar(0);
            } else {
                value = values.get(valueIndex);
                valueIndex++;
            }
            PackHelper.packW(value, output);
        }
        return valueIndex;
    }

    private static int handleNumericFormat(List<RuntimeScalar> values, int valueIndex, int count,
                                           char format, ParsedModifiers modifiers,
                                           ByteArrayOutputStream output) {
        for (int j = 0; j < count; j++) {
            RuntimeScalar value;
            if (valueIndex >= values.size()) {
                // If no more arguments, use 0 as per Perl behavior (empty string converts to 0)
                value = new RuntimeScalar(0);
            } else {
                value = values.get(valueIndex);
                valueIndex++;
            }

            // Check for Inf/NaN values for integer formats
            if (PackHelper.isIntegerFormat(format)) {
                PackHelper.handleInfinity(value, format);
            }

            System.err.println("DEBUG: handleNumericFormat processing format '" + format + "' with value: " + value.toString());

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
                    if (modifiers.bigEndian) {
                        PackWriter.writeShortBigEndian(output, value.getInt());
                    } else {
                        PackWriter.writeShortLittleEndian(output, value.getInt());
                    }
                    break;
                case 'S':
                    // Unsigned short - use endianness if specified
                    if (modifiers.bigEndian) {
                        PackWriter.writeShortBigEndian(output, value.getInt());
                    } else {
                        PackWriter.writeShort(output, value.getInt());
                    }
                    break;
                case 'l':
                    PackWriter.writeIntLittleEndian(output, (long) value.getDouble());
                    break;
                case 'L':
                case 'J':
                    PackWriter.writeLong(output, (long) value.getDouble());
                    break;
                case 'i':
                case 'I':
                    // Native integer (assume 32-bit little-endian)
                    PackWriter.writeIntLittleEndian(output, (long) value.getDouble());
                    break;
                case 'n':
                    // Network short (always big-endian)
                    PackWriter.writeShortBigEndian(output, value.getInt());
                    break;
                case 'N':
                    // Network long (always big-endian)
                    PackWriter.writeIntBigEndian(output, (long) value.getDouble());
                    break;
                case 'v':
                    // VAX short (always little-endian)
                    PackWriter.writeShortLittleEndian(output, value.getInt());
                    break;
                case 'V':
                    // VAX long (always little-endian)
                    PackWriter.writeIntLittleEndian(output, (long) value.getDouble());
                    break;
                case 'w':
                    // BER compressed integer
                    PackWriter.writeBER(output, (long) value.getDouble());
                    break;
                case 'j':
                    // Perl internal signed integer - treat as long
                    PackWriter.writeLong(output, (long) value.getDouble());
                    break;
                case 'q':
                    // Signed 64-bit quad
                    System.err.println("DEBUG: Processing q (signed quad) format");
                    PackWriter.writeLong(output, (long) value.getDouble());
                    break;
                case 'Q':
                    // Unsigned 64-bit quad
                    System.err.println("DEBUG: Processing Q (unsigned quad) format");
                    PackWriter.writeLong(output, (long) value.getDouble());
                    break;
                case 'f':
                    PackWriter.writeFloat(output, (float) value.getDouble());
                    break;
                case 'F':
                    // F is double-precision float in native format (8 bytes)
                    PackWriter.writeDouble(output, value.getDouble());
                    break;
                case 'd':
                    PackWriter.writeDouble(output, value.getDouble());
                    break;
                case 'D':
                    // Long double - treat as regular double in Java since we don't have long double
                    System.err.println("DEBUG: Processing D (long double) format as regular double");
                    PackWriter.writeDouble(output, value.getDouble());
                    break;
                default:
                    throw new PerlCompilerException("pack: unsupported format character: " + format);
            }
        }
        return valueIndex;
    }

}
