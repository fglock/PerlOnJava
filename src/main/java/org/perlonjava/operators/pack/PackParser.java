package org.perlonjava.operators.pack;

import org.perlonjava.operators.FormatModifierValidator;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.ArrayList;
import java.util.List;

/**
 * PackParser provides utility methods for parsing Perl pack template strings.
 *
 * <p>This class handles the parsing of various components in pack templates including:
 * <ul>
 *   <li>Endianness modifiers (&lt; for little-endian, &gt; for big-endian)</li>
 *   <li>Native size modifiers (! for native platform sizes)</li>
 *   <li>Repeat counts (numeric values, * for "use all remaining", or bracketed expressions)</li>
 *   <li>Group information for parenthesized template sections</li>
 * </ul>
 *
 * <p>Pack templates in Perl follow the format: [type][modifiers][count]
 * where modifiers can include endianness and native size indicators,
 * and count can be a number, *, or a bracketed expression.
 *
 * @see ParsedModifiers
 * @see ParsedCount
 * @see GroupInfo
 */
public class PackParser {

    /**
     * Parses endianness and native size modifiers from a pack template.
     *
     * <p>Modifiers are parsed in sequence after the template character:
     * <ul>
     *   <li>&lt; - little-endian byte order</li>
     *   <li>&gt; - big-endian byte order</li>
     *   <li>! - use native platform sizes</li>
     * </ul>
     *
     * @param template the pack template string to parse
     * @param position the current position in the template (should point to the type character)
     * @return ParsedModifiers object containing the parsed modifier flags and end position
     */
    public static ParsedModifiers parseModifiers(String template, int position) {
        ParsedModifiers result = new ParsedModifiers();
        result.endPosition = position;
        char formatChar = template.charAt(position);

        // Track modifiers in order for validation
        List<Character> modifiers = new ArrayList<>();

        while (result.endPosition + 1 < template.length()) {
            char modifier = template.charAt(result.endPosition + 1);
            if (modifier == '<') {
                if (result.bigEndian) {
                    throw new PerlCompilerException("Can't use both '<' and '>' after type '" + formatChar + "' in pack");
                }
                result.littleEndian = true;
                modifiers.add('<');
                result.endPosition++;
            } else if (modifier == '>') {
                if (result.littleEndian) {
                    throw new PerlCompilerException("Can't use both '<' and '>' after type '" + formatChar + "' in pack");
                }
                result.bigEndian = true;
                modifiers.add('>');
                result.endPosition++;
            } else if (modifier == '!') {
                result.nativeSize = true;
                modifiers.add('!');
                result.endPosition++;
            } else {
                break;
            }
        }

        // Use centralized validation system
        FormatModifierValidator.validateFormatModifiers(formatChar, modifiers, "pack");

        return result;
    }

    /**
     * Parses repeat count specifications from a pack template.
     *
     * <p>Repeat counts can be specified in several formats:
     * <ul>
     *   <li>Numeric: a simple integer (e.g., "5")</li>
     *   <li>Star: "*" meaning use all remaining data</li>
     *   <li>Bracketed: "[n]" for numeric count or "[template]" for template-based sizing</li>
     *   <li>Empty brackets: "[]" treated as count 0</li>
     * </ul>
     *
     * <p>For bracketed expressions containing templates (non-numeric content),
     * the method currently falls back to count 1 as template size calculation
     * is not yet fully implemented.
     *
     * @param template the pack template string to parse
     * @param position the current position in the template (should point to the type character)
     * @return ParsedCount object containing the parsed count, star flag, and end position
     * @throws PerlCompilerException if bracketed expression is not properly closed
     */
    public static ParsedCount parseRepeatCount(String template, int position) {
        ParsedCount result = new ParsedCount();
        result.count = 1;
        result.hasStar = false;
        result.endPosition = position;

        if (position + 1 < template.length()) {
            char nextChar = template.charAt(position + 1);
            if (nextChar == '[') {
                // Parse repeat count in brackets [n] or [template]
                int j = position + 2;
                int bracketDepth = 1;

                // Find the matching ']' with proper bracket depth counting
                while (j < template.length() && bracketDepth > 0) {
                    char ch = template.charAt(j);
                    if (ch == '[') {
                        bracketDepth++;
                    } else if (ch == ']') {
                        bracketDepth--;
                    }
                    if (bracketDepth > 0) {
                        j++;
                    }
                }

                if (j >= template.length() || bracketDepth > 0) {
                    throw new PerlCompilerException("No group ending character ']' found in template");
                }

                String countStr = template.substring(position + 2, j);

                // Check if it's purely numeric
                if (countStr.matches("\\d+")) {
                    result.count = Integer.parseInt(countStr);
                } else if (countStr.isEmpty()) {
                    // Empty brackets - treat as count 0
                    result.count = 0;
                } else {
                    // Template-based count - calculate the size of the template
                    result.count = calculateTemplateSize(countStr);
                }

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

    /**
     * Skips a comment in the template string, starting from the given position.
     *
     * <p>Comments in pack templates start with '#' and continue until the end of the line.
     * This method advances the position past the entire comment.</p>
     *
     * @param template The template string
     * @param position The starting position of the comment (should point to '#')
     * @return The position after the comment
     */
    public static int skipComment(String template, int position) {
        while (position + 1 < template.length() && template.charAt(position + 1) != '\n') {
            position++;
        }
        return position;
    }

    /**
     * Parses group information including modifiers and repeat counts for parenthesized template sections.
     *
     * <p>Groups in pack templates are enclosed in parentheses and can have modifiers and repeat counts
     * applied to the entire group. This method parses the content after the closing parenthesis ')'.
     *
     * <p>Supported group modifiers and counts:
     * <ul>
     *   <li>Endianness: &lt; or &gt;</li>
     *   <li>Native size: !</li>
     *   <li>Numeric repeat count</li>
     *   <li>Bracketed repeat count: [n]</li>
     *   <li>Star repeat: * (treated as Integer.MAX_VALUE)</li>
     * </ul>
     *
     * @param template the pack template string to parse
     * @param closePos the position of the closing parenthesis ')' in the template
     * @return GroupInfo object containing endianness, repeat count, and end position
     * @throws PerlCompilerException if bracketed repeat count is not properly closed
     */
    public static GroupInfo parseGroupInfo(String template, int closePos) {
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

    /**
     * Validates that the parsed modifiers are compatible with the format character.
     *
     * <p>Certain format characters do not support certain modifiers:
     * <ul>
     *   <li>h, H (hex): Do not support endianness (&lt;, &gt;) or native size (!) modifiers</li>
     *   <li>q, Q, j, J (quad/intmax): Do not support native size (!) or endianness modifiers</li>
     *   <li>f, F, d, D (float/double): Do not support native size (!) or endianness modifiers</li>
     *   <li>p, P (pointer): Do not support native size (!) or endianness modifiers</li>
     *   <li>n, N, v, V (network/VAX): Do not support endianness modifiers (have fixed byte order)</li>
     * </ul>
     *
     * @param formatChar the format character being validated
     * @param modifiers the parsed modifiers to validate
     * @param modifierOrder the order in which modifiers appeared in the template
     * @throws PerlCompilerException if incompatible modifiers are found
     */

    /**
     * Calculates the packed size in bytes for a given template.
     * This is used for x[template] constructs in unpack to determine how many bytes to skip.
     *
     * <p>This method works by actually packing dummy data with the template and measuring
     * the resulting byte length. This approach handles all format types correctly, including
     * variable-length formats like bit strings and hex strings.</p>
     *
     * @param template the pack template string
     * @return the number of bytes the template would produce when packed
     * @throws PerlCompilerException if the template contains invalid formats
     */
    public static int calculatePackedSize(String template) {
        // Validate that the template doesn't contain formats not allowed in x[template]
        if (template.contains("@") || template.contains(".") || template.contains("/") || template.contains("u")) {
            for (char c : template.toCharArray()) {
                if (c == '@' || c == '.' || c == '/' || c == 'u') {
                    throw new PerlCompilerException("Within []-length '" + c + "' not allowed in unpack");
                }
            }
        }

        // The best way to calculate the size is to actually pack dummy data and measure the result
        // This handles all the complex cases (bit strings, hex strings, groups, modifiers, etc.)
        try {
            // Create a RuntimeList with the template and enough dummy values
            org.perlonjava.runtime.RuntimeList args = new org.perlonjava.runtime.RuntimeList();
            args.add(new org.perlonjava.runtime.RuntimeScalar(template));

            // Add dummy values for each format character that needs data
            // Parse template to provide appropriate dummy values
            addDummyValuesForTemplate(template, args);

            // Pack the data and measure the result
            org.perlonjava.runtime.RuntimeScalar result = org.perlonjava.operators.Pack.pack(args);
            // Use byte length, not character length (important for UTF-8 data from W/U formats)
            // Get as ISO_8859_1 bytes to measure actual byte length
            return result.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1).length;

        } catch (Exception e) {
            // If packing fails, fall back to a simple estimation
            // This shouldn't happen for valid templates, but provides a safety net
            return 1;
        }
    }

    /**
     * Adds dummy values to args list for packing the given template.
     * Different format types need different dummy values to pack correctly.
     *
     * @param template the pack template string
     * @param args     the RuntimeList to add dummy values to
     */
    private static void addDummyValuesForTemplate(String template, org.perlonjava.runtime.RuntimeList args) {
        int i = 0;
        while (i < template.length()) {
            char format = template.charAt(i);

            // Skip whitespace and comments
            if (Character.isWhitespace(format)) {
                i++;
                continue;
            }
            if (format == '#') {
                // Skip to end of line or end of template
                while (i < template.length() && template.charAt(i) != '\n') {
                    i++;
                }
                i++;
                continue;
            }

            // Handle groups with repeat counts
            if (format == '(') {
                // Find matching closing parenthesis
                int depth = 1;
                int j = i + 1;
                while (j < template.length() && depth > 0) {
                    if (template.charAt(j) == '(') depth++;
                    else if (template.charAt(j) == ')') depth--;
                    j++;
                }
                
                if (depth > 0) {
                    // Unmatched parenthesis - skip it
                    i++;
                    continue;
                }
                
                // Extract group content (between parentheses)
                String groupContent = template.substring(i + 1, j - 1);
                i = j; // Move past closing paren
                
                // Parse repeat count after the group
                // Both (B)8 and (B)[8] mean repeat the group 8 times
                int groupRepeat = 1;
                if (i < template.length()) {
                    if (template.charAt(i) == '*') {
                        groupRepeat = 5; // Reasonable default
                        i++;
                    } else if (Character.isDigit(template.charAt(i))) {
                        // Numeric repeat like (B)8 means repeat the group 8 times
                        int k = i;
                        while (k < template.length() && Character.isDigit(template.charAt(k))) {
                            k++;
                        }
                        groupRepeat = Integer.parseInt(template.substring(i, k));
                        i = k;
                    } else if (template.charAt(i) == '[') {
                        // Bracket notation like (B)[8] also means repeat the group 8 times
                        int bracketStart = i + 1; // Skip the [
                        int bracketDepth = 1;
                        i++;
                        while (i < template.length() && bracketDepth > 0) {
                            if (template.charAt(i) == '[') bracketDepth++;
                            else if (template.charAt(i) == ']') bracketDepth--;
                            i++;
                        }
                        // Parse the number inside the brackets
                        String bracketContent = template.substring(bracketStart, i - 1);
                        if (bracketContent.matches("\\d+")) {
                            groupRepeat = Integer.parseInt(bracketContent);
                        } else {
                            groupRepeat = 1; // For non-numeric bracket content, treat as 1
                        }
                    }
                }
                
                // Recursively add dummy values for the group content, repeated groupRepeat times
                for (int r = 0; r < groupRepeat; r++) {
                    addDummyValuesForTemplate(groupContent, args);
                }
                continue;
            }
            
            // Skip closing parenthesis (should be handled in group processing above)
            if (format == ')') {
                i++;
                continue;
            }

            // Skip modifiers
            if (format == '<' || format == '>' || format == '!') {
                i++;
                continue;
            }

            // Parse repeat count
            int count = 1;
            i++; // Move past format character

            // Skip modifiers after format
            while (i < template.length() && (template.charAt(i) == '<' ||
                    template.charAt(i) == '>' || template.charAt(i) == '!')) {
                i++;
            }

            // Parse count
            if (i < template.length()) {
                if (template.charAt(i) == '*') {
                    count = 5; // Use a reasonable default for *
                    i++;
                } else if (Character.isDigit(template.charAt(i))) {
                    int j = i;
                    while (j < template.length() && Character.isDigit(template.charAt(j))) {
                        j++;
                    }
                    count = Integer.parseInt(template.substring(i, j));
                    i = j;
                } else if (template.charAt(i) == '[') {
                    // Skip bracketed expression
                    int bracketDepth = 1;
                    i++;
                    while (i < template.length() && bracketDepth > 0) {
                        if (template.charAt(i) == '[') bracketDepth++;
                        else if (template.charAt(i) == ']') bracketDepth--;
                        if (bracketDepth > 0) i++;
                    }
                    i++; // Move past closing bracket
                    count = 1; // Use default count for bracketed expressions
                }
            }

            // Add appropriate dummy values based on format type
            // Special case for P format: count is minimum string length, not repeat count
            // P always consumes exactly 1 value regardless of count
            int valuesToAdd = (format == 'P') ? 1 : count;
            
            for (int j = 0; j < valuesToAdd; j++) {
                switch (format) {
                    case 'a', 'A', 'Z' -> {
                        // String formats - provide a string
                        args.add(new org.perlonjava.runtime.RuntimeScalar("test"));
                    }
                    case 'p', 'P' -> {
                        // Pointer formats - provide a string
                        args.add(new org.perlonjava.runtime.RuntimeScalar("pointer"));
                    }
                    case 'x', 'X', '@' -> {
                        // These don't consume values
                    }
                    case 'b', 'B', 'h', 'H' -> {
                        // Bit and hex strings - provide a string
                        args.add(new org.perlonjava.runtime.RuntimeScalar("00"));
                    }
                    case 'u' -> {
                        // Uuencode - provide a string
                        args.add(new org.perlonjava.runtime.RuntimeScalar("test"));
                    }
                    default -> {
                        // Numeric formats - provide a number
                        args.add(new org.perlonjava.runtime.RuntimeScalar(0));
                    }
                }
            }
        }
    }

    /**
     * Counts how many values are needed to pack a template.
     * This is a helper method for calculatePackedSize.
     *
     * @param template the pack template string
     * @return estimated number of values needed
     */
    private static int countValuesNeeded(String template) {
        int count = 0;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            // Count format characters that consume values
            if (Character.isLetter(c) && c != 'x' && c != 'X' && c != '@') {
                count++;
            }
        }
        return Math.max(count, 10); // Ensure we have at least 10 values
    }

    /**
     * Calculates the total size in bytes of a template string.
     * For use with x[template] and X[template] formats.
     * <p>
     * This method uses the actual Pack.pack method to pack dummy data
     * and measure the resulting size, which handles all complex cases
     * including groups, modifiers, and nested templates correctly.
     *
     * @param template the template string
     * @return the total size in bytes
     */
    private static int calculateTemplateSize(String template) {
        // Use the actual pack method to calculate the size by packing dummy data
        // This handles all complex cases including groups, modifiers, etc.
        try {
            // Create a RuntimeList with the template and dummy values
            org.perlonjava.runtime.RuntimeList args = new org.perlonjava.runtime.RuntimeList();
            args.add(new org.perlonjava.runtime.RuntimeScalar(template));

            // Count how many values the template needs and add dummy values
            int valueCount = countValuesNeeded(template);
            for (int j = 0; j < valueCount; j++) {
                // Add dummy values - use 0 for numeric formats
                args.add(new org.perlonjava.runtime.RuntimeScalar(0));
            }

            // Pack with the template and measure the result
            org.perlonjava.runtime.RuntimeScalar result = org.perlonjava.operators.Pack.pack(args);
            return result.toString().length();
        } catch (Exception e) {
            // If packing fails for any reason, fall back to simple calculation
            // This might happen for templates with special requirements
            return calculateTemplateSizeSimple(template);
        }
    }

    /**
     * Simple fallback calculation for template size.
     * This is used when the pack method fails for some reason.
     */
    private static int calculateTemplateSizeSimple(String template) {
        // For simple fallback, just count format characters and use default sizes
        int size = 0;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (Character.isLetter(c) && c != 'X') {  // X is backward, doesn't add size
                size += getFormatSize(c, false);
            }
        }
        return Math.max(size, 1);  // Return at least 1
    }

    /**
     * Returns the size in bytes for a given format character.
     *
     * @param format     the format character
     * @param nativeSize whether the ! modifier is present
     * @return the size in bytes
     */
    private static int getFormatSize(char format, boolean nativeSize) {
        return switch (format) {
            case 'c', 'C', 'x', 'a', 'A', 'Z' -> 1;
            case 's', 'S', 'v', 'n' -> 2;
            case 'i', 'I', 'V', 'N', 'f', 'F' -> 4;
            case 'l', 'L' -> nativeSize ? 8 : 4; // Native long is 8 bytes with !
            case 'q', 'Q', 'j', 'J', 'd', 'D' -> 8;
            case 'p', 'P' -> 8; // Pointer size (always 8 on modern 64-bit systems)
            case 'w' -> 1; // BER compressed integer - variable but use 1 as base
            case 'b', 'B', 'h', 'H' -> 1; // Bit/hex strings - 1 byte per 2 chars (approximation)
            case 'X' -> 1; // Backward skip
            default -> 1; // Default to 1 byte for unknown formats
        };
    }
}
