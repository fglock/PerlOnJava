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
            ParsedModifiers modifiers = parseModifiers(template, i);
            i = modifiers.endPosition;

            // Check if this numeric format is part of a '/' construct BEFORE parsing count
            if (PackHelper.isNumericFormat(format) || format == 'Z') {
                int slashPos = PackHelper.checkForSlashConstruct(template, i);
                if (slashPos != -1) {
                    i = handleSlashConstruct(template, i, slashPos, format, values, valueIndex, output, modifiers);
                    valueIndex++;
                    continue;
                }
            }

            // Parse repeat count
            ParsedCount parsedCount = parseRepeatCount(template, i);
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
                case '/':
                    throw new PerlCompilerException("Invalid type '/'");
                case '@':
                    handleAbsolutePosition(count, output);
                    break;
                case 'p':
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

    private static ParsedModifiers parseModifiers(String template, int position) {
        ParsedModifiers result = new ParsedModifiers();
        result.endPosition = position;

        while (result.endPosition + 1 < template.length()) {
            char modifier = template.charAt(result.endPosition + 1);
            if (modifier == '<') {
                result.littleEndian = true;
                result.endPosition++;
            } else if (modifier == '>') {
                result.bigEndian = true;
                result.endPosition++;
            } else if (modifier == '!') {
                result.nativeSize = true;
                result.endPosition++;
            } else {
                break;
            }
        }

        return result;
    }

    private static ParsedCount parseRepeatCount(String template, int position) {
        ParsedCount result = new ParsedCount();
        result.count = 1;
        result.hasStar = false;
        result.endPosition = position;

        if (position + 1 < template.length()) {
            char nextChar = template.charAt(position + 1);
            if (nextChar == '[') {
                // Parse repeat count in brackets [n]
                int j = position + 2;
                while (j < template.length() && Character.isDigit(template.charAt(j))) {
                    j++;
                }
                if (j >= template.length() || template.charAt(j) != ']') {
                    throw new PerlCompilerException("No group ending character ']' found in template");
                }
                String countStr = template.substring(position + 2, j);
                result.count = Integer.parseInt(countStr);
                result.endPosition = j;
            } else if (Character.isDigit(nextChar)) {
                int j = position + 1;
                while (j < template.length() && Character.isDigit(template.charAt(j))) {
                    j++;
                }
                result.count = Integer.parseInt(template.substring(position + 1, j));
                result.endPosition = j - 1;
            } else if (nextChar == '*') {
                result.hasStar = true;
                result.endPosition = position + 1;
            }
        }

        return result;
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
        GroupInfo groupInfo = parseGroupInfo(template, closePos);

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

    private static GroupInfo parseGroupInfo(String template, int closePos) {
        GroupInfo info = new GroupInfo();
        int nextPos = closePos + 1;

        // Parse modifiers after ')'
        while (nextPos < template.length()) {
            char nextChar = template.charAt(nextPos);
            if (nextChar == '<' || nextChar == '>') {
                if (info.endian == ' ') {
                    info.endian = nextChar;
                }
                nextPos++;
            } else if (nextChar == '!') {
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
                info.repeatCount = Integer.parseInt(template.substring(nextPos + 1, j));
                nextPos = j + 1;
                break;
            } else if (Character.isDigit(nextChar)) {
                // Parse repeat count
                int j = nextPos;
                while (j < template.length() && Character.isDigit(template.charAt(j))) {
                    j++;
                }
                info.repeatCount = Integer.parseInt(template.substring(nextPos, j));
                nextPos = j;
                break;
            } else if (nextChar == '*') {
                info.repeatCount = Integer.MAX_VALUE;
                nextPos++;
                break;
            } else {
                break;
            }
        }

        info.endPosition = nextPos;
        return info;
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
        GroupInfo groupInfo = parseGroupInfo(template, groupEndPos);

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
        ParsedCount stringCountInfo = parseRepeatCount(template, stringPos);
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
            case 'n':
                PackHelper.writeShortBigEndian(output, length);
                break;
            case 'N':
                PackHelper.writeIntBigEndian(output, length);
                break;
            case 'v':
                PackHelper.writeShortLittleEndian(output, length);
                break;
            case 'V':
                PackHelper.writeIntLittleEndian(output, length);
                break;
            case 'w':
                PackHelper.writeBER(output, length);
                break;
            case 'C':
                output.write(length & 0xFF);
                break;
            case 's':
                if (modifiers.bigEndian) {
                    PackHelper.writeShortBigEndian(output, length);
                } else {
                    PackHelper.writeShortLittleEndian(output, length);
                }
                break;
            case 'S':
                if (modifiers.bigEndian) {
                    PackHelper.writeShortBigEndian(output, length);
                } else {
                    PackHelper.writeShort(output, length);
                }
                break;
            case 'i':
            case 'I':
            case 'l':
            case 'L':
                if (modifiers.bigEndian) {
                    PackHelper.writeIntBigEndian(output, length);
                } else {
                    PackHelper.writeIntLittleEndian(output, length);
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
        if (valueIndex >= values.size()) {
            throw new PerlCompilerException("pack: not enough arguments");
        }
        RuntimeScalar value = values.get(valueIndex++);
        String bitString = value.toString();
        if (hasStar) {
            count = bitString.length();
        }
        PackHelper.writeBitString(output, bitString, count, format);
        return valueIndex;
    }

    private static int handleHexString(List<RuntimeScalar> values, int valueIndex, char format,
                                       int count, boolean hasStar, ByteArrayOutputStream output) {
        if (valueIndex >= values.size()) {
            throw new PerlCompilerException("pack: not enough arguments");
        }
        RuntimeScalar value = values.get(valueIndex++);
        String hexString = value.toString();
        if (hasStar) {
            count = hexString.length();
        }
        PackHelper.writeHexString(output, hexString, count, format);
        return valueIndex;
    }

    private static int handleUuencode(List<RuntimeScalar> values, int valueIndex, ByteArrayOutputStream output) {
        if (valueIndex >= values.size()) {
            throw new PerlCompilerException("pack: not enough arguments");
        }
        RuntimeScalar value = values.get(valueIndex++);
        String str = value.toString();
        PackHelper.writeUuencodedString(output, str);
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

    private static int handlePointer(List<RuntimeScalar> values, int valueIndex, int count,
                                     ParsedModifiers modifiers, ByteArrayOutputStream output) {
        for (int j = 0; j < count; j++) {
            if (valueIndex >= values.size()) {
                throw new PerlCompilerException("pack: not enough arguments");
            }

            RuntimeScalar value = values.get(valueIndex++);
            int ptr = 0;

            // Check if value is defined (not undef)
            if (value.getDefinedBoolean()) {
                String str = value.toString();
                ptr = str.hashCode();
                pointerMap.put(ptr, str);
            }

            // Use the already-parsed endianness
            if (modifiers.bigEndian) {
                PackHelper.writeIntBigEndian(output, ptr);
            } else {
                PackHelper.writeIntLittleEndian(output, ptr);
            }
        }
        return valueIndex;
    }

    private static boolean handleUnicode(List<RuntimeScalar> values, int valueIndex, int count,
                                         boolean byteMode, boolean hasUnicodeInNormalMode,
                                         ByteArrayOutputStream output) {
        for (int j = 0; j < count; j++) {
            if (valueIndex + j >= values.size()) {
                throw new PerlCompilerException("pack: not enough arguments");
            }
            RuntimeScalar value = values.get(valueIndex + j);
            hasUnicodeInNormalMode = PackHelper.packU(value, byteMode, hasUnicodeInNormalMode, output);
        }
        return hasUnicodeInNormalMode;
    }

    private static int handleWideCharacter(List<RuntimeScalar> values, int valueIndex, int count,
                                           ByteArrayOutputStream output) {
        for (int j = 0; j < count; j++) {
            if (valueIndex >= values.size()) {
                throw new PerlCompilerException("pack: not enough arguments");
            }
            RuntimeScalar value = values.get(valueIndex++);
            PackHelper.packW(value, output);
        }
        return valueIndex;
    }

    private static int handleNumericFormat(List<RuntimeScalar> values, int valueIndex, int count,
                                           char format, ParsedModifiers modifiers,
                                           ByteArrayOutputStream output) {
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
                    if (modifiers.bigEndian) {
                        PackHelper.writeShortBigEndian(output, value.getInt());
                    } else {
                        PackHelper.writeShortLittleEndian(output, value.getInt());
                    }
                    break;
                case 'S':
                    // Unsigned short - use endianness if specified
                    if (modifiers.bigEndian) {
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
        return valueIndex;
    }

    private static class ParsedModifiers {
        boolean bigEndian;
        boolean littleEndian;
        boolean nativeSize;
        int endPosition;
    }

    private static class ParsedCount {
        int count;
        boolean hasStar;
        int endPosition;
    }

    private static class GroupInfo {
        char endian = ' ';
        int repeatCount = 1;
        int endPosition;
    }
}
