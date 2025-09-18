package org.perlonjava.operators;

import org.perlonjava.operators.pack.*;
import org.perlonjava.runtime.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    public static final Map<Character, PackFormatHandler> handlers = new HashMap<>();

    static {
        // Initialize format handlers
        handlers.put('b', new BitStringPackHandler('b'));
        handlers.put('B', new BitStringPackHandler('B'));
        handlers.put('h', new HexStringPackHandler('h'));
        handlers.put('H', new HexStringPackHandler('H'));
        handlers.put('u', new UuencodePackHandler());
        handlers.put('p', new PointerPackHandler());
        handlers.put('P', new PointerPackHandler());
        handlers.put('W', new WideCharacterPackHandler());
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
            // DEBUG: main loop i=" + i + ", format='" + format + "' (code " + (int) format + ")"

            // Skip spaces
            if (Character.isWhitespace(format)) {
                // DEBUG: skipping whitespace
                continue;
            }

            // Skip comments
            if (format == '#') {
                i = PackParser.skipComment(template, i);
                continue;
            }

            // Check for misplaced brackets
            if (format == '[') {
                throw new PerlCompilerException("Mismatched brackets in template");
            }
            if (format == ']') {
                throw new PerlCompilerException("Mismatched brackets in template");
            }

            // Handle commas (skip with warning)
            if (format == ',') {
                // WARNING: Invalid type ',' in pack
                // In Perl, this would use warn() but continue execution
                continue;
            }

            // Handle parentheses for grouping
            if (format == '(') {
                i = PackGroupHandler.handleGroup(template, i, values, output, valueIndex, Pack::pack);
                valueIndex = PackGroupHandler.getValueIndexAfterGroup(template, i, values, valueIndex);
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
                    // DEBUG: Detected slash construct for format '" + format + "' at position " + i
                    i = PackGroupHandler.handleSlashConstruct(template, i, slashPos, format, values, valueIndex, output, modifiers, Pack::pack);
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
                        valueIndex = PackHelper.handleStringFormat(valueIndex, values, hasStar, format, count, byteMode, output);
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
                    case 'U':
                        // Unicode format needs special handling for state management
                        hasUnicodeInNormalMode = PackHelper.handleUnicode(values, valueIndex, count, byteMode, hasUnicodeInNormalMode, output);
                        valueIndex += count;
                        break;
                    default:
                        // Check for misplaced brackets
                        if (format == '[' || format == ']') {
                            throw new PerlCompilerException("Mismatched brackets in template");
                        }
                        throw new PerlCompilerException("pack: unsupported format character: " + format);
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
}
