package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.operators.pack.*;
import org.perlonjava.runtime.runtimetypes.*;

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
 * @see Unpack
 * @see PackHelper
 * @see PackParser
 * @see PackWriter
 */
public class Pack {
    /**
     * Enable trace output for pack operations.
     * Set to true to debug pack template processing.
     */
    private static final boolean TRACE_PACK = false;

    private static final ThreadLocal<Stack<Integer>> groupBaseStack = ThreadLocal.withInitial(() -> {
        Stack<Integer> stack = new Stack<>();
        stack.push(0);
        return stack;
    });

    public static void pushGroupBase(int base) {
        groupBaseStack.get().push(base);
    }

    public static void popGroupBase() {
        Stack<Integer> stack = groupBaseStack.get();
        if (stack.size() > 1) {
            stack.pop();
        }
    }

    public static int getCurrentGroupBase() {
        Stack<Integer> stack = groupBaseStack.get();
        return stack.isEmpty() ? 0 : stack.peek();
    }

    /**
     * Get the group base at a specific level.
     * Level 1 = innermost group (same as getCurrentGroupBase)
     * Level 2 = parent group
     * Level 0 or beyond stack depth = absolute position (0)
     *
     * @param level The group level (1-based, or 0 for absolute)
     * @return The group base at the specified level
     */
    public static int getGroupBaseAtLevel(int level) {
        Stack<Integer> stack = groupBaseStack.get();
        if (level == 0 || stack.size() <= 1) {
            // Level 0 or no groups: absolute position
            return 0;
        }
        // Level 1 = top of stack (innermost), level 2 = one below, etc.
        int index = stack.size() - level;
        if (index < 0) {
            // Level exceeds depth: return absolute position
            return 0;
        }
        return stack.get(index);
    }

    public static void adjustGroupBasesAfterTruncate(int newSize) {
        Stack<Integer> stack = groupBaseStack.get();
        for (int i = 0; i < stack.size(); i++) {
            if (stack.get(i) > newSize) {
                stack.set(i, newSize);
            }
        }
    }

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
        if (args.isEmpty()) {
            return RuntimeScalarCache.scalarEmptyString;
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
        int valueIndex = 0;

        PackResult result = packInto(template, values, valueIndex, output, false, false);

        boolean shouldUpgrade = !result.byteModeUsed()
                && (result.hasUnicodeInNormalMode() || output.hasUnicodeCharacters());

        if (shouldUpgrade) {
            return new RuntimeScalar(output.toUpgradedString());
        }
        return new RuntimeScalar(output.toByteArray());
    }

    public static PackResult packInto(String template, List<RuntimeScalar> values, int startValueIndex,
                                      PackBuffer output, boolean initialByteMode, boolean initialHasUnicode) {
        int valueIndex = startValueIndex;

        // Pre-scan template for C0 to determine initial mode
        // If C0 appears anywhere, start in byte mode from the beginning
        boolean byteMode = initialByteMode || template.contains("C0");  // Start in byte mode if C0 is present
        boolean byteModeUsed = byteMode;  // Track if byte mode was ever used

        // U0/C0 also act as a scoped switch for how string formats (a/A/Z) interpret their input.
        // In particular, U0 triggers Perl's UTF-8 byte decoding semantics for a/A/Z.
        boolean utf8StringMode = false;

        // Track if 'U' was used in normal mode (not byte mode)
        boolean hasUnicodeInNormalMode = initialHasUnicode;

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
        for (int i = 0; i < template.length(); i++) {
            char format = template.charAt(i);
            // DEBUG: main loop i=" + i + ", format='" + format + "' (code " + (int) format + ")"

            // Skip spaces - whitespace is ignored for formatting/readability
            if (Character.isWhitespace(format)) {
                // DEBUG: skipping whitespace
                continue;
            }

            // Skip comments - run from # to end of line
            if (format == '#') {
                i = PackParser.skipComment(template, i);
                continue;
            }

            // Safety check for misplaced brackets
            // Brackets should only appear immediately after a format character, not standalone
            if (format == '[' || format == ']') {
                throw new PerlCompilerException("Invalid type '" + format + "' in pack");
            }

            // Handle commas - ignored for Perl compatibility but emit warning
            if (format == ',') {
                // Emit warning like Perl does
                WarnDie.warn(
                        new RuntimeScalar("Invalid type ',' in pack"),
                        RuntimeScalarCache.scalarEmptyString
                );
                continue;
            }

            /**
             * Handle groups - RECURSIVE PROCESSING
             *
             * Format: (template)count or (template)[count]
             * Example: (si)3 packs 3 short-int pairs
             *
             * Processing:
             * 1. handleGroup finds the matching ')'
             * 2. Extracts the group content
             * 3. Recursively calls Pack.pack() for the group content
             * 4. Repeats for the specified count
             *
             * NESTING: Each group recursion should increment depth counter.
             * Deep nesting (>100 levels) should throw "Too deeply nested ()-groups"
             */
            if (format == '(') {
                if (TRACE_PACK) {
                    System.err.println("TRACE Pack: Found '(' at position " + i);
                    System.err.println("  Calling PackGroupHandler.handleGroup");
                    System.err.flush();
                }
                PackGroupHandler.GroupResult result = PackGroupHandler.handleGroup(
                        template,
                        i,
                        values,
                        output,
                        valueIndex,
                        byteMode,
                        byteModeUsed,
                        hasUnicodeInNormalMode
                );
                i = result.position();
                valueIndex = result.valueIndex();
                byteMode = result.byteMode();
                byteModeUsed = result.byteModeUsed();
                hasUnicodeInNormalMode = result.hasUnicodeInNormalMode();
                if (TRACE_PACK) {
                    System.err.println("TRACE Pack: Group processed, new position: " + i);
                    System.err.flush();
                }
                continue;
            }

            // Check for mode modifiers C0 and U0
            if (format == 'C' && i + 1 < template.length() && template.charAt(i + 1) == '0') {
                byteMode = true;        // C0 switches to byte mode
                byteModeUsed = true;    // Mark that byte mode was used
                utf8StringMode = false; // C0 disables UTF-8 byte decoding semantics for a/A/Z
                i++; // Skip the '0'
                continue;
            } else if (format == 'U' && i + 1 < template.length() && template.charAt(i + 1) == '0') {
                byteMode = false;       // U0 switches to normal mode
                utf8StringMode = true;  // U0 enables UTF-8 byte decoding semantics for a/A/Z
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

            // Parse modifiers BEFORE parsing counts
            ParsedModifiers modifiers = PackParser.parseModifiers(template, i);

            // Check if this numeric format is part of a '/' construct
            // Check from current position i (not after modifiers) to catch S / A* with spaces
            if (PackHelper.isNumericFormat(format) || format == 'Z' || format == 'A' || format == 'a') {
                int slashPos = PackHelper.checkForSlashConstruct(template, i);
                if (slashPos != -1) {
                    // DEBUG: Detected slash construct for format '" + format + "' at position " + i
                    PackGroupHandler.GroupResult result = PackGroupHandler.handleSlashConstruct(
                            template,
                            i,
                            slashPos,
                            format,
                            values,
                            valueIndex,
                            output,
                            modifiers,
                            byteMode,
                            byteModeUsed,
                            hasUnicodeInNormalMode
                    );
                    i = result.position();
                    valueIndex = result.valueIndex();
                    byteMode = result.byteMode();
                    byteModeUsed = result.byteModeUsed();
                    hasUnicodeInNormalMode = result.hasUnicodeInNormalMode();
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

            // Check if '/' has a repeat count (which is invalid)
            if (format == '/' && (count > 1 || hasStar)) {
                throw new PerlCompilerException("'/' does not take a repeat count");
            }

            if (hasStar && count == 1) {
                count = values.size() - valueIndex; // Use all remaining values
            }

            // Handle the format using handlers where possible
            PackFormatHandler handler = handlers.get(format);
            if (handler != null) {
                valueIndex = handler.pack(values, valueIndex, count, hasStar, modifiers, output);
            } else {
                // Handle special cases that need state management or don't have handlers yet
                switch (format) {
                    case 'a':
                    case 'A':
                    case 'Z':
                        // These still use PackHelper due to byteMode dependency
                        valueIndex = PackHelper.handleStringFormat(valueIndex, values, hasStar, format, count, utf8StringMode, output);
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
                            throw new PerlCompilerException("Invalid type '/' in pack");
                        }
                    case 'U':
                        // Unicode format needs special handling for state management
                        hasUnicodeInNormalMode = PackHelper.handleUnicode(values, valueIndex, count, byteMode, hasUnicodeInNormalMode, output);
                        valueIndex += count;
                        break;
                    case 'W':
                        // Wide character format - like U but without Unicode range validation
                        hasUnicodeInNormalMode = PackHelper.handleWideCharacter(values, valueIndex, count, byteMode, hasUnicodeInNormalMode, output);
                        valueIndex += count;
                        break;
                    default:
                        // Check for misplaced brackets
                        if (format == '[' || format == ']') {
                            throw new PerlCompilerException("Mismatched brackets in template");
                        }
                        // Check for standalone * which is invalid
                        if (format == '*') {
                            throw new PerlCompilerException("Invalid type '*' in pack");
                        }
                        // Check for standalone modifiers that should only appear after valid format characters
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

        // Return the packing result with final state
        return new PackResult(valueIndex, byteMode, byteModeUsed, hasUnicodeInNormalMode);
    }

    /**
     * Result of packing operation, containing final state.
     */
    public static record PackResult(int valueIndex, boolean byteMode, boolean byteModeUsed, boolean hasUnicodeInNormalMode) {
    }
}
