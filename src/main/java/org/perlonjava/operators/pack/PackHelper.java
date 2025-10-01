package org.perlonjava.operators.pack;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalarType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Helper utility class for Perl's pack operation.
 * 
 * <p>This class provides various utility methods to support the pack operation,
 * which converts a list of values into a binary string according to a template.
 * The pack operation is the counterpart to unpack and is used for creating
 * binary data structures, network protocols, and file formats.</p>
 * 
 * <p>Key functionality includes:</p>
 * <ul>
 *   <li>Format validation and type checking</li>
 *   <li>Template parsing and analysis</li>
 *   <li>String and numeric format handling</li>
 *   <li>Unicode and UTF-8 encoding support</li>
 *   <li>Endianness conflict detection</li>
 * </ul>
 * 
 * @see org.perlonjava.operators.Pack
 * @see org.perlonjava.operators.Unpack
 */
public class PackHelper {

    /**
     * Check if the format is an integer format that should reject Inf/NaN values.
     * 
     * <p>Integer formats in Perl's pack operation cannot handle infinite or
     * NaN (Not a Number) values and should throw exceptions when such values
     * are encountered.</p>
     * 
     * @param format the pack format character to check
     * @return true if the format is an integer format, false otherwise
     */
    public static boolean isIntegerFormat(char format) {
        return switch (format) {
            case 'c', 'C', 's', 'S', 'l', 'L', 'i', 'I', 'n', 'N', 'v', 'V', 'w', 'W', 'q', 'Q', 'j', 'J', 'U', 'Z' -> true;
            default -> false;
        };
    }

    /**
     * Check if a format at the given position is part of a '/' construct.
     * 
     * <p>In Perl's pack templates, the '/' construct allows using the result
     * of one format as the count for another format. For example, in "n/a*",
     * the 'n' format provides the count for the 'a' format that follows.</p>
     * 
     * <p>This method analyzes the template to determine if a format character
     * is part of such a construct by looking ahead for modifiers, counts,
     * and the '/' separator.</p>
     * 
     * @param template The complete pack template string
     * @param position The position of the format character to check
     * @return The position of '/' if found, or -1 if not part of '/' construct
     */
    public static int checkForSlashConstruct(String template, int position) {
        int lookAhead = position + 1;

        // Skip modifiers (<, >, !)
        while (lookAhead < template.length() &&
                (template.charAt(lookAhead) == '<' ||
                        template.charAt(lookAhead) == '>' ||
                        template.charAt(lookAhead) == '!')) {
            lookAhead++;
        }

        // Skip whitespace
        while (lookAhead < template.length() && Character.isWhitespace(template.charAt(lookAhead))) {
            lookAhead++;
        }

        // Skip count or *
        if (lookAhead < template.length()) {
            if (template.charAt(lookAhead) == '*') {
                lookAhead++;
            } else if (template.charAt(lookAhead) == '[') {
                // Handle [n] style counts
                lookAhead++; // skip '['
                while (lookAhead < template.length() && Character.isDigit(template.charAt(lookAhead))) {
                    lookAhead++;
                }
                if (lookAhead < template.length() && template.charAt(lookAhead) == ']') {
                    lookAhead++;
                }
            } else if (Character.isDigit(template.charAt(lookAhead))) {
                while (lookAhead < template.length() && Character.isDigit(template.charAt(lookAhead))) {
                    lookAhead++;
                }
            }
        }

        // Skip more whitespace after count
        while (lookAhead < template.length() && Character.isWhitespace(template.charAt(lookAhead))) {
            lookAhead++;
        }

        // Check if followed by '/'
        if (lookAhead < template.length() && template.charAt(lookAhead) == '/') {
            // DEBUG: Found slash at position " + lookAhead + " after format at position " + position
            return lookAhead;
        }

        return -1;
    }

    /**
     * Handle string format processing for pack operations.
     * 
     * <p>String formats (a, A, Z, b, B, h, H, u) in pack templates consume
     * exactly one value from the input list, regardless of the repeat count.
     * This method processes the string value and writes it to the output
     * according to the specified format and count.</p>
     * 
     * <p>Special handling includes:</p>
     * <ul>
     *   <li>Using empty string when no more values are available</li>
     *   <li>Handling '*' count by using string length</li>
     *   <li>Proper null termination for 'Z' format</li>
     *   <li>Byte mode considerations for 'a' format</li>
     * </ul>
     * 
     * @param valueIndex current index in the values list
     * @param values list of input values to pack
     * @param hasStar true if the format uses '*' as count
     * @param format the string format character (a, A, Z, etc.)
     * @param count the repeat count for the format
     * @param byteMode true if operating in byte mode
     * @param output the output stream to write packed data
     * @return updated value index after consuming the string value
     */
    public static int handleStringFormat(int valueIndex, List<RuntimeScalar> values, boolean hasStar, char format, int count, boolean byteMode, ByteArrayOutputStream output) {
        // String formats consume only one value
        RuntimeScalar value;
        if (valueIndex >= values.size()) {
            // If no more arguments, use empty string as per Perl behavior
            value = new RuntimeScalar("");
        } else {
            value = values.get(valueIndex);
            valueIndex++;
        }

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
            PackWriter.writeString(output, str, count, format, byteMode);
        } else {
            PackWriter.writeString(output, str, count, format, false);
        }
        return valueIndex;
    }

    /**
     * Handle infinite and NaN values for integer formats.
     * 
     * <p>Integer pack formats cannot handle infinite or NaN values.
     * This method checks for these special floating-point values and
     * throws appropriate exceptions with descriptive error messages.</p>
     * 
     * <p>Recognized infinite/NaN representations:</p>
     * <ul>
     *   <li>"Inf", "+Inf", "Infinity" - positive infinity</li>
     *   <li>"-Inf", "-Infinity" - negative infinity</li>
     *   <li>"NaN" - Not a Number</li>
     * </ul>
     * 
     * @param value the scalar value to check
     * @param format the pack format character (used for error messages)
     * @throws PerlCompilerException if the value is infinite or NaN
     */
    public static void handleInfinity(RuntimeScalar value, char format) {
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

    /**
     * Pack a value using the 'w' format (BER compressed integer).
     * 
     * <p>The 'w' format packs an unsigned integer using BER (Basic Encoding Rules)
     * compression. For Unicode characters, this method encodes them as UTF-8.
     * Values beyond the Unicode range are wrapped to fit within valid Unicode
     * code points.</p>
     * 
     * <p>Processing logic:</p>
     * <ul>
     *   <li>If input is a character, use its code point</li>
     *   <li>If input is numeric, use the value directly</li>
     *   <li>Valid Unicode code points are encoded as UTF-8</li>
     *   <li>Invalid code points are wrapped to valid range</li>
     * </ul>
     * 
     * @param value the scalar value to pack
     * @param output the output stream to write packed data
     * @throws RuntimeException if I/O error occurs during writing
     */
    /**
     * Pack a wide character using the 'W' format.
     * W format is like U format but without Unicode range validation.
     * It accepts any integer value and stores it as UTF-8 bytes.
     * 
     * @param value the scalar value to pack
     * @param byteMode whether we are in byte mode
     * @param hasUnicodeInNormalMode whether we have already used Unicode in normal mode
     * @param output the output stream to write packed data
     * @return whether we have used Unicode in normal mode
     */
    public static boolean packW(RuntimeScalar value, boolean byteMode, boolean hasUnicodeInNormalMode, ByteArrayOutputStream output) {
        // Check for Inf/NaN first, before any other processing
        handleInfinity(value, 'W');
        
        int codePoint;
        String strValue = value.toString();
        if (!strValue.isEmpty() && !Character.isDigit(strValue.charAt(0))) {
            // If it's a character, get its code point
            codePoint = strValue.codePointAt(0);
        } else {
            // If it's a number, use it directly
            codePoint = value.getInt();
        }

        // Track if W is used in character mode (not byte mode)
        if (!byteMode) {
            hasUnicodeInNormalMode = true;
        }

        // W format always writes UTF-8 encoded bytes
        // The difference between modes is handled at the final string conversion
        // Unlike U format, W doesn't validate the Unicode range
        try {
            if (Character.isValidCodePoint(codePoint)) {
                String unicodeChar = new String(Character.toChars(codePoint));
                byte[] utf8Bytes = unicodeChar.getBytes(StandardCharsets.UTF_8);
                output.write(utf8Bytes);
            } else {
                // Beyond Unicode range - wrap to valid range without throwing exception
                int wrappedValue = codePoint & 0x1FFFFF; // 21 bits
                if (wrappedValue > 0x10FFFF) {
                    wrappedValue = wrappedValue % 0x110000; // Modulo to fit in Unicode range
                }
                String unicodeChar = new String(Character.toChars(wrappedValue));
                byte[] utf8Bytes = unicodeChar.getBytes(StandardCharsets.UTF_8);
                output.write(utf8Bytes);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return hasUnicodeInNormalMode;
    }

    /**
     * Pack a Unicode character using the 'U' format.
     * 
     * <p>The 'U' format packs Unicode code points as UTF-8 encoded bytes.
     * This method handles both character and numeric inputs, validates
     * Unicode code points, and tracks whether Unicode is used in normal
     * (non-byte) mode for proper string conversion.</p>
     * 
     * <p>Processing steps:</p>
     * <ul>
     *   <li>Extract code point from character or numeric input</li>
     *   <li>Validate that code point is within Unicode range</li>
     *   <li>Encode as UTF-8 and write to output stream</li>
     *   <li>Track Unicode usage in normal mode</li>
     * </ul>
     * 
     * @param value the scalar value containing the Unicode code point
     * @param byteMode true if operating in byte mode
     * @param hasUnicodeInNormalMode current state of Unicode usage tracking
     * @param output the output stream to write UTF-8 encoded bytes
     * @return updated Unicode usage state for normal mode
     * @throws PerlCompilerException if the code point is invalid
     * @throws RuntimeException if I/O error occurs during writing
     */
    public static boolean packU(RuntimeScalar value, boolean byteMode, boolean hasUnicodeInNormalMode, ByteArrayOutputStream output) {
        // Pack a Unicode character number as UTF-8
        // Check for Inf/NaN first, before any other processing
        handleInfinity(value, 'U');
        
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

    /**
     * Handles a Unicode format.
     * 
     * @param values The list of values to pack
     * @param valueIndex The current index in the values list
     * @param count The repeat count
     * @param byteMode Whether we are in byte mode
     * @param hasUnicodeInNormalMode Whether we have already used Unicode in normal mode
     * @param output The output stream
     * @return Whether we have used Unicode in normal mode
     */
    public static boolean handleUnicode(List<RuntimeScalar> values, int valueIndex, int count,
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

    /**
     * Handles a wide character format (W).
     * W format is like U format but without Unicode range validation.
     * 
     * @param values The list of values to pack
     * @param valueIndex The current index in the values list
     * @param count The repeat count
     * @param byteMode Whether we are in byte mode
     * @param hasUnicodeInNormalMode Whether we have already used Unicode in normal mode
     * @param output The output stream
     * @return Whether we have used Unicode in normal mode
     */
    public static boolean handleWideCharacter(List<RuntimeScalar> values, int valueIndex, int count,
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
            hasUnicodeInNormalMode = PackHelper.packW(value, byteMode, hasUnicodeInNormalMode, output);
        }
        return hasUnicodeInNormalMode;
    }

    /**
     * Packs the length of a string according to the specified format.
     * 
     * @param output The output stream
     * @param format The format character
     * @param length The length to pack
     * @param modifiers The modifiers for the format
     */
    public static void packLength(ByteArrayOutputStream output, char format, int length, ParsedModifiers modifiers) {
        // DEBUG: packing length " + length + " with format '" + format + "'"

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

    /**
     * Count the number of values needed to satisfy a pack template.
     * 
     * <p>This method analyzes a pack template to determine how many input
     * values will be consumed during the pack operation. This is essential
     * for validating that sufficient arguments are provided.</p>
     * 
     * <p>Counting rules:</p>
     * <ul>
     *   <li>String formats (a, A, Z, etc.) consume exactly 1 value</li>
     *   <li>Numeric formats consume 1 value per repeat count</li>
     *   <li>Positioning formats (x, X, @) consume no values</li>
     *   <li>Groups with repeat counts multiply the inner count</li>
     *   <li>Formats with '*' count return Integer.MAX_VALUE</li>
     *   <li>Formats in N/ constructs don't consume values</li>
     * </ul>
     * 
     * @param template the pack template string to analyze
     * @return number of values needed, or Integer.MAX_VALUE if unlimited
     */
    public static int countValuesNeeded(String template) {
        int count = 0;
        // DEBUG countValuesNeeded: template='" + template + "'
        for (int i = 0; i < template.length(); i++) {
            char format = template.charAt(i);
            // DEBUG countValuesNeeded: i=" + i + ", format='" + format + "', count=" + count

            if (Character.isWhitespace(format) || format == '#') {
                continue;
            }

            // Skip comments
            if (format == '#') {
                while (i + 1 < template.length() && template.charAt(i + 1) != '\n') {
                    i++;
                }
                continue;
            }

            if (format == '(') {
                int closePos = findMatchingParen(template, i);
                if (closePos != -1) {
                    // Count values needed for the group content
                    String groupContent = template.substring(i + 1, closePos);
                    int groupCount = countValuesNeeded(groupContent);

                    // Skip past the group and check for repeat count
                    i = closePos;
                    // Skip modifiers
                    while (i + 1 < template.length() &&
                            (template.charAt(i + 1) == '<' ||
                                    template.charAt(i + 1) == '>' ||
                                    template.charAt(i + 1) == '!')) {
                        i++;
                    }

                    // Check for repeat count after group
                    int repeatCount = 1;
                    if (i + 1 < template.length()) {
                        if (template.charAt(i + 1) == '*') {
                            // DEBUG countValuesNeeded: returning MAX_VALUE due to group with *
                            return Integer.MAX_VALUE;
                        } else if (Character.isDigit(template.charAt(i + 1))) {
                            int j = i + 1;
                            while (j < template.length() && Character.isDigit(template.charAt(j))) {
                                j++;
                            }
                            repeatCount = Integer.parseInt(template.substring(i + 1, j));
                            i = j - 1;
                        }
                    }

                    count += groupCount * repeatCount;
                }
                continue;
            }

            // FIRST: Check if this numeric format is part of a '/' construct
            if (isNumericFormat(format) || format == 'Z') {
                int slashPos = checkForSlashConstruct(template, i);
                if (slashPos != -1) {
                    // DEBUG countValuesNeeded: format '" + format + "' at " + i + " is part of N/ construct, skipping to " + slashPos
                    // This numeric format is part of N/X construct
                    // It doesn't consume a value - skip to the '/'
                    i = slashPos - 1; // -1 because loop will increment
                    continue;
                }
            }

            // Handle '/' - it doesn't consume values itself
            if (format == '/') {
                // Skip whitespace after '/'
                int j = i + 1;
                while (j < template.length() && Character.isWhitespace(template.charAt(j))) {
                    j++;
                }
                if (j < template.length()) {
                    // Skip the string format after '/'
                    i = j;
                    format = template.charAt(i);
                    // DEBUG countValuesNeeded: '/' skipping to format '" + format + "' at position " + i
                }
                // Fall through to count the string format normally
            }

            // Skip modifiers
            while (i + 1 < template.length() &&
                    (template.charAt(i + 1) == '<' ||
                            template.charAt(i + 1) == '>' ||
                            template.charAt(i + 1) == '!')) {
                i++;
            }

            // Parse repeat count
            int repeatCount = 1;
            boolean hasStar = false;
            if (i + 1 < template.length()) {
                if (template.charAt(i + 1) == '*') {
                    hasStar = true;
                    i++;
                    // DEBUG countValuesNeeded: found * after format '" + format + "'
                } else if (template.charAt(i + 1) == '[') {
                    // Parse repeat count in brackets [n]
                    int j = i + 2;
                    while (j < template.length() && Character.isDigit(template.charAt(j))) {
                        j++;
                    }
                    if (j < template.length() && template.charAt(j) == ']') {
                        repeatCount = Integer.parseInt(template.substring(i + 2, j));
                        i = j;
                    }
                } else if (Character.isDigit(template.charAt(i + 1))) {
                    int j = i + 1;
                    while (j < template.length() && Character.isDigit(template.charAt(j))) {
                        j++;
                    }
                    repeatCount = Integer.parseInt(template.substring(i + 1, j));
                    i = j - 1;
                }
            }

            // Count based on format
            if ("xX@".indexOf(format) >= 0) {
                // These don't consume values
                continue;
            } else if ("aAZbBhHu".indexOf(format) >= 0) {
                // String/binary formats consume exactly one value regardless of repeat count
                count += 1;
                // DEBUG countValuesNeeded: string format '" + format + "' adds 1, count=" + count
            } else if (isNumericFormat(format) || format == 'p' || format == '.') {
                // Numeric formats consume 'repeatCount' values (or return MAX for *)
                if (hasStar) {
                    // DEBUG countValuesNeeded: returning MAX_VALUE due to numeric format '" + format + "' with *
                    return Integer.MAX_VALUE;
                }
                count += repeatCount;
                // DEBUG countValuesNeeded: numeric format '" + format + "' adds " + repeatCount + ", count=" + count
            }
        }
        // DEBUG countValuesNeeded: returning " + count
        return count;
    }

    /**
     * Check if a group has conflicting endianness modifiers.
     * 
     * <p>In pack templates, groups can have endianness modifiers (&lt; for little-endian,
     * &gt; for big-endian) that apply to all formats within the group. This method
     * detects conflicts where individual formats within the group specify
     * different endianness than the group's setting.</p>
     * 
     * <p>Conflict detection includes:</p>
     * <ul>
     *   <li>Direct format modifiers conflicting with group endianness</li>
     *   <li>Nested groups with conflicting endianness</li>
     *   <li>Recursive checking of nested group contents</li>
     * </ul>
     * 
     * @param groupContent the content inside the group parentheses
     * @param groupEndian the endianness modifier for the group ('&lt;' or '&gt;')
     * @return true if conflicting endianness is detected, false otherwise
     */
    public static boolean hasConflictingEndianness(String groupContent, char groupEndian) {
        // Check if the group content has endianness modifiers that conflict with the group's endianness
        for (int i = 0; i < groupContent.length(); i++) {
            char c = groupContent.charAt(i);

            // Check for nested groups with conflicting endianness
            if (c == '(') {
                int closePos = findMatchingParen(groupContent, i);
                if (closePos != -1) {
                    // Check for endianness modifier after the nested group
                    int checkPos = closePos + 1;
                    while (checkPos < groupContent.length()) {
                        char modifier = groupContent.charAt(checkPos);
                        if (modifier == '<' || modifier == '>') {
                            if ((modifier == '<' && groupEndian == '>') ||
                                    (modifier == '>' && groupEndian == '<')) {
                                return true;
                            }
                            break;
                        } else if (modifier == '!') {
                            checkPos++;
                        } else {
                            break;
                        }
                    }
                    // Also recursively check inside the nested group
                    String nestedContent = groupContent.substring(i + 1, closePos);
                    if (hasConflictingEndianness(nestedContent, groupEndian)) {
                        return true;
                    }
                    i = closePos;
                }
            } else if ((c == '<' || c == '>') && i > 0) {
                // Check if this is a modifier for a format that supports it
                char prevChar = groupContent.charAt(i - 1);
                if ("sSiIlLqQjJfFdDpP".indexOf(prevChar) >= 0) {
                    if ((c == '<' && groupEndian == '>') || (c == '>' && groupEndian == '<')) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Find the position of the closing parenthesis that matches an opening parenthesis.
     * 
     * <p>This method performs balanced parenthesis matching to find the closing
     * parenthesis that corresponds to an opening parenthesis at the specified
     * position. It properly handles nested groups by tracking the nesting depth.</p>
     * 
     * @param template the pack template string
     * @param openPos the position of the opening parenthesis
     * @return the position of the matching closing parenthesis, or -1 if not found
     */
    public static int findMatchingParen(String template, int openPos) {
        int depth = 1;
        for (int i = openPos + 1; i < template.length(); i++) {
            if (template.charAt(i) == '(') depth++;
            else if (template.charAt(i) == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * Check if a format character represents a numeric format.
     * 
     * <p>Numeric formats in pack templates represent various integer and
     * floating-point types with different sizes and byte orders. This method
     * identifies which format characters are considered numeric.</p>
     * 
     * <p>Numeric formats include:</p>
     * <ul>
     *   <li>c, C - signed/unsigned char</li>
     *   <li>s, S - signed/unsigned short</li>
     *   <li>l, L - signed/unsigned long</li>
     *   <li>i, I - signed/unsigned int</li>
     *   <li>n, N - network byte order short/long</li>
     *   <li>v, V - VAX byte order short/long</li>
     *   <li>w, W - BER compressed integer</li>
     *   <li>q, Q - signed/unsigned quad (64-bit)</li>
     *   <li>j, J - signed/unsigned intmax</li>
     *   <li>Z - null-terminated string (can be used as count)</li>
     * </ul>
     * 
     * @param format the format character to check
     * @return true if the format is numeric, false otherwise
     */
    public static boolean isNumericFormat(char format) {
        return switch (format) {
            case 'c', 'C', 's', 'S', 'l', 'L', 'i', 'I', 'n', 'N', 'v', 'V', 'w', 'W', 'q', 'Q', 'j', 'J',
                 'Z' ->  // Z can be used as a count
                    true;
            default -> false;
        };
    }

}
