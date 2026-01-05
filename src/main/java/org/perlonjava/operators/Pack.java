package org.perlonjava.operators;

import org.perlonjava.operators.pack.*;
import org.perlonjava.runtime.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

/**
 * The Pack class provides functionality to pack a list of scalars into a binary string
 * based on a specified template, similar to Perl's pack function.
 *
 * <p>This class implements Perl's pack() function, which takes a template string and a list
 * of values, and converts them into a packed binary string according to the format
 * specifications in the template.</p>
 *
 * <p>Supported format characters include:</p>
 * <ul>
 *   <li><b>a, A, Z</b> - ASCII string formats (null-padded, space-padded, null-terminated)</li>
 *   <li><b>b, B</b> - Bit string formats (ascending/descending bit order)</li>
 *   <li><b>h, H</b> - Hex string formats (low/high nibble first)</li>
 *   <li><b>c, C</b> - Signed/unsigned char (8-bit)</li>
 *   <li><b>s, S</b> - Signed/unsigned short (16-bit)</li>
 *   <li><b>i, I, l, L</b> - Signed/unsigned int/long (32-bit)</li>
 *   <li><b>q, Q, j, J</b> - Signed/unsigned quad (64-bit)</li>
 *   <li><b>f, F, d, D</b> - Float formats (single/double precision)</li>
 *   <li><b>n, N, v, V</b> - Network/VAX byte order formats</li>
 *   <li><b>U, W</b> - Unicode formats</li>
 *   <li><b>p, P</b> - Pointer formats</li>
 *   <li><b>u</b> - UUencoded string</li>
 *   <li><b>w</b> - BER compressed integer</li>
 *   <li><b>x, X</b> - Null padding and backup</li>
 *   <li><b>@, .</b> - Absolute positioning</li>
 * </ul>
 *
 * <p>The class also supports:</p>
 * <ul>
 *   <li>Repeat counts (e.g., "i4" for 4 integers)</li>
 *   <li>Star notation (e.g., "i*" for all remaining values)</li>
 *   <li>Grouping with parentheses (e.g., "(si)3" for 3 short-int pairs)</li>
 *   <li>Endianness modifiers (&lt; for little-endian, &gt; for big-endian)</li>
 *   <li>Slash constructs for length-prefixed data (e.g., "n/a*")</li>
 *   <li>Mode switching (C0 for byte mode, U0 for character mode)</li>
 * </ul>
 *
 * @see org.perlonjava.operators.Unpack
 * @see org.perlonjava.operators.pack.PackHelper
 * @see org.perlonjava.operators.pack.PackParser
 * @see org.perlonjava.operators.pack.PackWriter
 */
public class Pack {
    /**
     * Enable trace output for pack operations.
     * Set to true to debug pack template processing.
     */
    private static final boolean TRACE_PACK = false;
    
    public static final Map<Character, PackFormatHandler> handlers = new HashMap<>();

    static {
        // Initialize format handlers
        handlers.put('b', new BitStringPackHandler('b'));
        handlers.put('B', new BitStringPackHandler('B'));
        handlers.put('h', new HexStringPackHandler('h'));
        handlers.put('H', new HexStringPackHandler('H'));
        handlers.put('u', new UuencodePackHandler());
        handlers.put('p', new PointerPackHandler('p'));
        handlers.put('P', new PointerPackHandler('P'));
        // W format is handled specially like U format (see switch statement below)
        // handlers.put('W', new WideCharacterPackHandler());
        handlers.put('x', new ControlPackHandler('x'));
        handlers.put('X', new ControlPackHandler('X'));
        handlers.put('@', new ControlPackHandler('@'));
        handlers.put('.', new ControlPackHandler('.'));

        // Numeric format handlers
        handlers.put('c', new NumericPackHandler('c'));
        handlers.put('C', new NumericPackHandler('C'));
        handlers.put('s', new NumericPackHandler('s'));
        handlers.put('S', new NumericPackHandler('S'));
        handlers.put('i', new NumericPackHandler('i'));
        handlers.put('I', new NumericPackHandler('I'));
        handlers.put('l', new NumericPackHandler('l'));
        handlers.put('L', new NumericPackHandler('L'));
        handlers.put('q', new NumericPackHandler('q'));
        handlers.put('Q', new NumericPackHandler('Q'));
        handlers.put('j', new NumericPackHandler('j'));
        handlers.put('J', new NumericPackHandler('J'));
        handlers.put('f', new NumericPackHandler('f'));
        handlers.put('F', new NumericPackHandler('F'));
        handlers.put('d', new NumericPackHandler('d'));
        handlers.put('D', new NumericPackHandler('D'));
        handlers.put('n', new NumericPackHandler('n'));
        handlers.put('N', new NumericPackHandler('N'));
        handlers.put('v', new NumericPackHandler('v'));
        handlers.put('V', new NumericPackHandler('V'));
        handlers.put('w', new NumericPackHandler('w'));
    }

    /**
     * Retrieves a string value associated with a pointer hash code.
     * This method is used by the unpack operation to retrieve strings
     * that were stored during pack operations with 'p' or 'P' formats.
     *
     * @param hashCode The hash code of the string to retrieve
     * @return The string associated with the hash code, or null if not found
     */
    public static String getPointerString(int hashCode) {
        return PointerPackHandler.getPointerString(hashCode);
    }

    /**
     * Packs a list of RuntimeScalar objects into a binary string according to the specified template.
     * This is the main entry point for the pack operation.
     *
     * <p>The first argument must be a template string that specifies how to pack the remaining
     * arguments. The template consists of format characters, optional repeat counts, and
     * various modifiers.</p>
     *
     * <p>Examples:</p>
     * <pre>
     * pack("C*", 65, 66, 67)        // Pack as unsigned chars: "ABC"
     * pack("n", 1234)               // Pack as network short: 2-byte big-endian
     * pack("a10", "hello")          // Pack as 10-byte null-padded string
     * pack("(si)2", 1, 100, 2, 200) // Pack 2 short-int pairs
     * </pre>
     *
     * @param args A RuntimeList containing the template string followed by the values to pack
     * @return A RuntimeScalar representing the packed binary string
     * @throws PerlCompilerException if there are not enough arguments, invalid format characters,
     *                              mismatched brackets, or other template parsing errors
     */

    /**
     * Validates bracket matching in a template using proper stack-based algorithm.
     * Returns null if brackets are properly matched, or error message if mismatched.
     *
     * @param template the template string to validate
     * @return null if valid, error message if invalid
     */
    private static String validateBracketMatching(String template) {
        Stack<Character> stack = new Stack<>();

        for (int i = 0; i < template.length(); i++) {
            char ch = template.charAt(i);

            // Push opening brackets onto stack
            if (ch == '[' || ch == '(') {
                stack.push(ch);
            }
            // Check closing brackets
            else if (ch == ']' || ch == ')') {
                if (stack.isEmpty()) {
                    return "Mismatched brackets in template";
                }

                char top = stack.pop();

                // Check if bracket types match
                if ((ch == ']' && top != '[') || (ch == ')' && top != '(')) {
                    return "Mismatched brackets in template";
                }
            }
        }

        // Check for unmatched opening brackets
        if (!stack.isEmpty()) {
            // If stack contains only '[' characters, use specific message
            boolean onlySquareBrackets = true;
            for (Character c : stack) {
                if (c != '[') {
                    onlySquareBrackets = false;
                    break;
                }
            }

            if (onlySquareBrackets) {
                return "No group ending character ']' found in template";
            } else {
                return "Mismatched brackets in template";
            }
        }

        return null; // All brackets properly matched
    }

    public static RuntimeScalar pack(RuntimeList args) {
        try {
            if (args.isEmpty()) {
                throw new PerlCompilerException("pack: not enough arguments");
            }

            RuntimeScalar templateScalar = args.getFirst();
            String template = templateScalar.toString();
        
        if (TRACE_PACK) {
            System.err.println("TRACE Pack.pack() called:");
            System.err.println("  template: [" + template + "]");
            System.err.println("  args count: " + (args.size() - 1));
            System.err.flush();
        }

        // Validate bracket matching using proper stack-based algorithm
        String bracketError = validateBracketMatching(template);
        if (bracketError != null) {
            throw new PerlCompilerException(bracketError);
        }

        // Flatten the remaining arguments into a RuntimeArray
        List<RuntimeBase> remainingArgs = args.elements.subList(1, args.elements.size());
        RuntimeArray flattened = new RuntimeArray(remainingArgs.toArray(new RuntimeBase[0]));
        List<RuntimeScalar> values = flattened.elements;

        PackBuffer output = new PackBuffer();

        // Start in character mode. C0/U0 directives toggle mode at their position.
        boolean byteMode = false;
        boolean byteModeUsed = false;

        // Track if 'U' was used in normal mode (not byte mode)
        boolean hasUnicodeInNormalMode = false;

        /**
         * Main parsing loop - process template character by character
         * 
         * The template is processed left-to-right. Each character determines:
         * 1. What type of data to pack (format character like 's', 'i', 'a', etc.)
         * 2. How many values to consume (repeat count)
         * 3. How to modify the operation (modifiers like '<', '>', '!')
         * 
         * Key concepts:
         * - Groups (): Allow applying repeat counts to multiple formats
         * - Slash constructs (n/a*): Pack length-prefixed data  
         * - Mode switches (C0/U0): Change between byte and character mode
         * - Repeat counts: *, digits, or [n] notation
         * 
         * The loop variable 'i' tracks current position. Some operations advance
         * 'i' beyond the current character to skip processed content.
         */
        PackState state = packInto(template, values, output, 0, byteMode, byteModeUsed, hasUnicodeInNormalMode);

        byteModeUsed = state.byteModeUsed;
        hasUnicodeInNormalMode = state.hasUnicodeInNormalMode;

            // Convert buffer to string based on whether UTF-8 flag should be set
            if (!byteModeUsed && hasUnicodeInNormalMode) {
                // UTF-8 flag set: interpret all values as Latin-1 characters
                // This matches Perl's utf8::upgrade behavior where each byte becomes a character
                return new RuntimeScalar(output.toUpgradedString());
            } else {
                // No UTF-8 flag: return as byte string
                return new RuntimeScalar(output.toByteArray());
            }
        } catch (OutOfMemoryError e) {
            throw new PerlCompilerException("Out of memory during pack");
        }
    }

    private record PackState(int valueIndex, boolean byteMode, boolean byteModeUsed, boolean hasUnicodeInNormalMode) {
    }

    private static PackState packInto(String template, List<RuntimeScalar> values, PackBuffer output,
                                      int valueIndex, boolean byteMode, boolean byteModeUsed,
                                      boolean hasUnicodeInNormalMode) {
        for (int i = 0; i < template.length(); i++) {
            int formatPos = i;
            char format = template.charAt(i);

            // Skip spaces - whitespace is ignored for formatting/readability
            if (Character.isWhitespace(format)) {
                continue;
            }

            // Skip comments - run from # to end of line
            if (format == '#') {
                i = PackParser.skipComment(template, i);
                continue;
            }

            // Safety check for misplaced brackets
            if (format == '[' || format == ']') {
                throw new PerlCompilerException("Invalid type '" + format + "' in pack");
            }

            // Handle commas - ignored for Perl compatibility but emit warning
            if (format == ',') {
                WarnDie.warn(
                        new RuntimeScalar("Invalid type ',' in pack"),
                        RuntimeScalarCache.scalarEmptyString
                );
                continue;
            }

            if (format == '(') {
                int closePos = PackHelper.findMatchingParen(template, i);
                if (closePos == -1) {
                    throw new PerlCompilerException("pack: unmatched parenthesis in template");
                }

                String groupContent = template.substring(i + 1, closePos);
                GroupInfo groupInfo = PackParser.parseGroupInfo(template, closePos);

                if (groupInfo.endian != ' ' && PackHelper.hasConflictingEndianness(groupContent, groupInfo.endian)) {
                    throw new PerlCompilerException("Can't use '" + groupInfo.endian + "' in a group with different byte-order in pack");
                }

                String effectiveContent = groupContent;
                if (groupInfo.endian != ' ') {
                    effectiveContent = GroupEndiannessHelper.applyGroupEndianness(groupContent, groupInfo.endian);
                }

                int groupValueCount = PackHelper.countValuesNeeded(groupContent);
                int repeatCount = groupInfo.repeatCount;

                boolean outerByteMode = byteMode;

                if (repeatCount == Integer.MAX_VALUE) {
                    // Repeat as many complete iterations as possible.
                    // If the group consumes no values, '*' would otherwise loop forever.
                    if (groupValueCount <= 0 || groupValueCount == Integer.MAX_VALUE) {
                        // Zero iterations
                    } else {
                        while (values.size() - valueIndex >= groupValueCount) {
                            int beforeValueIndex = valueIndex;
                            output.pushGroupStart(output.size());
                            PackState inner = packInto(effectiveContent, values, output, valueIndex, outerByteMode, byteModeUsed, hasUnicodeInNormalMode);
                            output.popGroupStart();
                            valueIndex = inner.valueIndex;
                            byteModeUsed = inner.byteModeUsed;
                            hasUnicodeInNormalMode = inner.hasUnicodeInNormalMode;

                            if (valueIndex == beforeValueIndex) {
                                break;
                            }
                        }
                    }
                } else {
                    for (int rep = 0; rep < repeatCount; rep++) {
                        output.pushGroupStart(output.size());
                        PackState inner = packInto(effectiveContent, values, output, valueIndex, outerByteMode, byteModeUsed, hasUnicodeInNormalMode);
                        output.popGroupStart();
                        valueIndex = inner.valueIndex;
                        byteModeUsed = inner.byteModeUsed;
                        hasUnicodeInNormalMode = inner.hasUnicodeInNormalMode;
                    }
                }

                // Mode switches inside the group are scoped.
                byteMode = outerByteMode;

                i = groupInfo.endPosition - 1;
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

            // Check for standalone modifiers that should only appear after valid format characters
            if (format == '<' || format == '>') {
                throw new PerlCompilerException("Invalid type '" + format + "' in pack");
            }
            if (format == '!') {
                throw new PerlCompilerException("Invalid type '!' in pack");
            }

            ParsedModifiers modifiers = PackParser.parseModifiers(template, formatPos);

            if (PackHelper.isNumericFormat(format) || format == 'Z' || format == 'A' || format == 'a') {
                int slashPos = PackHelper.checkForSlashConstruct(template, i);
                if (slashPos != -1) {
                    PackGroupHandler.GroupResult result = PackGroupHandler.handleSlashConstruct(template, i, slashPos, format, values, valueIndex, output, modifiers, Pack::pack);
                    i = result.position();
                    valueIndex = result.valueIndex();
                    continue;
                }
            }

            ParsedCount parsedCount = PackParser.parseRepeatCount(template, modifiers.endPosition);
            int count = parsedCount.count;
            boolean hasStar = parsedCount.hasStar;
            
            // Update loop counter to skip past the count we just parsed
            // The for-loop will increment i at the end of this iteration
            i = parsedCount.endPosition;

            if (format == '/' && (count > 1 || hasStar)) {
                throw new PerlCompilerException("'/' does not take a repeat count");
            }

            if (hasStar && count == 1) {
                // Per perldoc -f pack:
                // - @, x, X: '*' is equivalent to 0
                // - .: '*' has special meaning and is handled in ControlPackHandler
                // - otherwise: consume all remaining values
                if (format == '@' || format == 'x' || format == 'X') {
                    count = 0;
                } else if (format != '.') {
                    count = values.size() - valueIndex;
                }
            }

            PackFormatHandler handler = handlers.get(format);
            if (handler != null) {
                valueIndex = handler.pack(values, valueIndex, count, hasStar, modifiers, output);
            } else {
                switch (format) {
                    case 'a':
                    case 'A':
                    case 'Z':
                        valueIndex = PackHelper.handleStringFormat(valueIndex, values, hasStar, format, count, byteMode, output);
                        break;
                    case '/':
                        int nextPos = i + 1;
                        while (nextPos < template.length() && Character.isWhitespace(template.charAt(nextPos))) {
                            nextPos++;
                        }
                        if (nextPos >= template.length()) {
                            throw new PerlCompilerException("Code missing after '/'");
                        } else {
                            throw new PerlCompilerException("Invalid type '/' in pack");
                        }
                    case 'U':
                        hasUnicodeInNormalMode = PackHelper.handleUnicode(values, valueIndex, count, byteMode, hasUnicodeInNormalMode, output);
                        valueIndex += count;
                        break;
                    case 'W':
                        hasUnicodeInNormalMode = PackHelper.handleWideCharacter(values, valueIndex, count, byteMode, hasUnicodeInNormalMode, output);
                        valueIndex += count;
                        break;
                    default:
                        if (format == '[' || format == ']') {
                            throw new PerlCompilerException("Mismatched brackets in template");
                        }
                        if (format == '*') {
                            throw new PerlCompilerException("Invalid type '*' in pack");
                        }
                        if (format == '<' || format == '>') {
                            throw new PerlCompilerException("Invalid type '" + format + "' in pack");
                        }
                        if (format == '!') {
                            throw new PerlCompilerException("Invalid type '!' in pack");
                        }
                        throw new PerlCompilerException("Invalid type '" + format + "' in pack");
                }
            }
        }

        return new PackState(valueIndex, byteMode, byteModeUsed, hasUnicodeInNormalMode);
    }
}
