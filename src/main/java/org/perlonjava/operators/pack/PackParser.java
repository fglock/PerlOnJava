package org.perlonjava.operators.pack;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.operators.FormatModifierValidator;
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
                    // Template-based count - for now, use 1 as fallback
                    result.count = 1;
                    // TODO: Implement proper template size calculation
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
}
