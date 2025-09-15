package org.perlonjava.operators;

import org.perlonjava.operators.unpack.*;
import org.perlonjava.runtime.*;

import java.util.*;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * Provides functionality to unpack binary data into a list of scalars
 * based on a specified template, similar to Perl's unpack function.
 */
public class Unpack {
    private static final Map<Character, FormatHandler> handlers = new HashMap<>();

    static {
        // Initialize format handlers
        handlers.put('c', new CFormatHandler(true));   // signed char
        handlers.put('C', new CFormatHandler(false));  // unsigned char
        handlers.put('S', new NumericFormatHandler.ShortHandler(false));
        handlers.put('s', new NumericFormatHandler.ShortHandler(true));
        handlers.put('L', new NumericFormatHandler.LongHandler(false));
        handlers.put('l', new NumericFormatHandler.LongHandler(true));
        handlers.put('i', new NumericFormatHandler.LongHandler(true));   // signed int
        handlers.put('I', new NumericFormatHandler.LongHandler(false));  // unsigned int
        handlers.put('N', new NumericFormatHandler.NetworkLongHandler());
        handlers.put('n', new NumericFormatHandler.NetworkShortHandler());
        handlers.put('V', new NumericFormatHandler.VAXLongHandler());
        handlers.put('v', new NumericFormatHandler.VAXShortHandler());
        handlers.put('f', new NumericFormatHandler.FloatHandler());
        handlers.put('F', new NumericFormatHandler.DoubleHandler());  // F is double-precision like d
        handlers.put('d', new NumericFormatHandler.DoubleHandler());
        handlers.put('a', new StringFormatHandler('a'));
        handlers.put('A', new StringFormatHandler('A'));
        handlers.put('Z', new StringFormatHandler('Z'));
        handlers.put('b', new BitStringFormatHandler('b'));
        handlers.put('B', new BitStringFormatHandler('B'));
        handlers.put('h', new HexStringFormatHandler('h'));
        handlers.put('H', new HexStringFormatHandler('H'));
        handlers.put('W', new WFormatHandler());
        handlers.put('x', new XFormatHandler());
        handlers.put('X', new XBackwardHandler());  // Add this line
        handlers.put('w', new WBERFormatHandler());
        handlers.put('p', new PointerFormatHandler());
        handlers.put('u', new UuencodeFormatHandler());
        handlers.put('@', new AtFormatHandler());  // Add this line
        // Note: U handler is created dynamically based on startsWithU
    }

    /**
     * unpack(template, data)
     *
     * @param args
     * @return
     */
    public static RuntimeList unpack(int ctx, RuntimeBase... args) {
        RuntimeScalar templateScalar = (RuntimeScalar) args[0];
        RuntimeScalar packedData = args.length > 1 ? args[1].scalar() : scalarUndef;

        String template = templateScalar.toString();
        String dataString = packedData.toString();

        // Default mode is always C0 (character mode)
        boolean startsWithU = template.startsWith("U") && !template.startsWith("U0");

        // Create state object - always starts in character mode
        UnpackState state = new UnpackState(dataString, false);

        // Check if template starts with U0 to switch to byte mode
        if (template.startsWith("U0")) {
            state.switchToByteMode();
        }

        RuntimeList out = new RuntimeList();
        List<RuntimeBase> values = out.elements;

        // Stack to track mode for parentheses scoping
        Stack<Boolean> modeStack = new Stack<>();

        // Parse template
        int i = 0;
        while (i < template.length()) {
            char format = template.charAt(i);
            boolean isChecksum = false;
            int checksumBits = 16; // default

            // Check for checksum modifier
            if (format == '%') {
                isChecksum = true;
                i++;
                if (i >= template.length()) {
                    throw new PerlCompilerException("unpack: '%' must be followed by a format");
                }

                // Parse optional bit count
                if (Character.isDigit(template.charAt(i))) {
                    int j = i;
                    while (j < template.length() && Character.isDigit(template.charAt(j))) {
                        j++;
                    }
                    checksumBits = Integer.parseInt(template.substring(i, j));
                    i = j;
                    if (i >= template.length()) {
                        throw new PerlCompilerException("unpack: '%' must be followed by a format");
                    }
                }

                // Get the actual format after %
                format = template.charAt(i);
            }

            // Handle parentheses for grouping
            if (format == '(') {
                // Find matching closing parenthesis
                int closePos = findMatchingParen(template, i);
                if (closePos == -1) {
                    throw new PerlCompilerException("unpack: unmatched parenthesis in template");
                }

                // Check for endianness modifier after the group
                char groupEndian = ' '; // default: no specific endianness
                int nextPos = closePos + 1;
                if (nextPos < template.length()) {
                    char nextChar = template.charAt(nextPos);
                    if (nextChar == '<' || nextChar == '>') {
                        groupEndian = nextChar;
                        nextPos++;
                    }
                }

                // Extract group content
                String groupContent = template.substring(i + 1, closePos);

                // Check for conflicting endianness within the group
                if (groupEndian != ' ' && hasConflictingEndianness(groupContent, groupEndian)) {
                    throw new PerlCompilerException("Can't use both '<' and '>' in a group with different byte-order in unpack");
                }

                // Check for repeat count after closing paren
                int groupRepeatCount = 1;

                if (nextPos < template.length()) {
                    char nextChar = template.charAt(nextPos);
                    if (nextChar == '*') {
                        // Repeat until end of data
                        groupRepeatCount = Integer.MAX_VALUE;
                        nextPos++;
                    } else if (Character.isDigit(nextChar)) {
                        // Parse numeric repeat count
                        int j = nextPos;
                        while (j < template.length() && Character.isDigit(template.charAt(j))) {
                            j++;
                        }
                        groupRepeatCount = Integer.parseInt(template.substring(nextPos, j));
                        nextPos = j;
                    }
                }

                // Push current mode onto stack
                modeStack.push(state.isCharacterMode());

                // Process the group
                processGroup(groupContent, state, values, groupRepeatCount, startsWithU, modeStack);

                // Restore mode from stack
                if (!modeStack.isEmpty()) {
                    boolean savedMode = modeStack.pop();
                    if (savedMode) {
                        state.switchToCharacterMode();
                    } else {
                        state.switchToByteMode();
                    }
                }

                // Move past the group
                i = nextPos;
                continue;
            }

            // Skip whitespace
            if (Character.isWhitespace(format)) {
                i++;
                continue;
            }

            // Skip comments
            if (format == '#') {
                // Skip to end of line or end of template
                while (i + 1 < template.length() && template.charAt(i + 1) != '\n') {
                    i++;
                }
                i++;
                continue;
            }

            // Handle endianness modifiers that might appear after certain formats
            if ((format == '<' || format == '>') && i > 0) {
                // This is likely a modifier for the previous format, skip it
                i++;
                continue;
            }

            // Handle '/' for counted strings
            if (format == '/') {
                if (values.isEmpty()) {
                    throw new PerlCompilerException("'/' must follow a numeric type");
                }

                // Get the count from the last unpacked value
                RuntimeBase lastValue = values.get(values.size() - 1);
                int slashCount = ((RuntimeScalar) lastValue).getInt();
                values.remove(values.size() - 1); // Remove the count value

                // Get the string format that follows '/'
                i++;
                if (i >= template.length()) {
                    throw new PerlCompilerException("Code missing after '/'");
                }
                char stringFormat = template.charAt(i);

                if (stringFormat != 'a' && stringFormat != 'A' && stringFormat != 'Z') {
                    throw new PerlCompilerException("'/' must be followed by a string type");
                }

                // Parse optional count/star after string format
                boolean hasStarAfterSlash = false;
                if (i + 1 < template.length() && template.charAt(i + 1) == '*') {
                    hasStarAfterSlash = true;
                    i++; // Move to the '*'
                }

                // Unpack the string with the count from the previous numeric value
                FormatHandler stringHandler = handlers.get(stringFormat);
                stringHandler.unpack(state, values, slashCount, hasStarAfterSlash);

                // IMPORTANT: Skip past all characters we've processed
                // The continue statement will skip the normal i++ at the end of the loop
                i++;  // Move past the last character we processed
                continue;
            }

            // Check for explicit mode modifiers
            if (format == 'C' && i + 1 < template.length() && template.charAt(i + 1) == '0') {
                state.switchToCharacterMode();
                i += 2; // Skip 'C0'
                continue;
            } else if (format == 'U' && i + 1 < template.length() && template.charAt(i + 1) == '0') {
                state.switchToByteMode();
                i += 2; // Skip 'U0'
                continue;
            }

            // Parse count
            int count = 1;
            boolean isStarCount = false;

            // First, skip any '!' modifiers after the format character
            while (i + 1 < template.length() && template.charAt(i + 1) == '!') {
                i++; // Skip '!' - for unpack, it doesn't change behavior
            }

            if (i + 1 < template.length()) {
                char nextChar = template.charAt(i + 1);
                if (nextChar == '*') {
                    isStarCount = true;
                    i++;
                    count = getRemainingCount(state, format, startsWithU);
                } else if (Character.isDigit(nextChar)) {
                    int j = i + 1;
                    while (j < template.length() && Character.isDigit(template.charAt(j))) {
                        j++;
                    }
                    String countStr = template.substring(i + 1, j);
                    count = Integer.parseInt(countStr);
                    if (format == '@') {
                        System.err.println("DEBUG: @ count string '" + countStr + "' parsed to " + count);
                    }
                    i = j - 1;
                }
            }

            // Get handler and unpack
            FormatHandler handler = getHandler(format, startsWithU);
            if (handler != null) {
                if (format == '@') {
                    System.err.println("DEBUG: Calling @ handler with count=" + count);
                }
                // For 'p' format, check and consume endianness modifiers
                if (format == 'p' && i + 1 < template.length()) {
                    char nextChar = template.charAt(i + 1);
                    if (nextChar == '<' || nextChar == '>') {
                        i++; // consume the modifier
                        // Note: For our simple implementation, we ignore endianness
                        // since we're using hashCode which is already platform-specific
                    }
                }

                if (isChecksum) {
                    // Handle checksum calculation
                    List<RuntimeBase> tempValues = new ArrayList<>();
                    handler.unpack(state, tempValues, count, isStarCount);

                    // Calculate checksum based on format
                    long checksum = 0;
                    if (format == 'b' || format == 'B') {
                        // For binary formats, count 1 bits
                        for (RuntimeBase value : tempValues) {
                            String binary = value.toString();
                            for (int j = 0; j < binary.length(); j++) {
                                if (binary.charAt(j) == '1') {
                                    checksum++;
                                }
                            }
                        }
                    } else {
                        // For other formats, sum the numeric values
                        for (RuntimeBase value : tempValues) {
                            checksum += ((RuntimeScalar) value).getInt();
                        }
                    }

                    // Apply bit mask based on checksumBits
                    if (checksumBits == 16) {
                        checksum &= 0xFFFF;
                    } else if (checksumBits == 32) {
                        checksum &= 0xFFFFFFFFL;
                    }

                    // For 32-bit checksums that would be negative as int, convert to long
                    if (checksumBits == 32 && checksum > Integer.MAX_VALUE) {
                        values.add(new RuntimeScalar(checksum));
                    } else {
                        values.add(new RuntimeScalar((int) checksum));
                    }
                } else {
                    handler.unpack(state, values, count, isStarCount);
                }
            } else {
                throw new PerlCompilerException("unpack: unsupported format character: " + format);
            }

            i++;
        }
        if (ctx == RuntimeContextType.SCALAR && !out.isEmpty()) {
            return out.elements.getFirst().getList();
        }
        return out;
    }

    private static FormatHandler getHandler(char format, boolean startsWithU) {
        if (format == 'U') {
            return new UFormatHandler(startsWithU);
        }
        return handlers.get(format);
    }

    private static int getRemainingCount(UnpackState state, char format, boolean startsWithU) {
        switch (format) {
            case 'C':
            case 'U':
                if (state.isCharacterMode() || (format == 'U' && startsWithU)) {
                    return state.remainingCodePoints();
                } else {
                    return state.remainingBytes();
                }
            case 'a':
            case 'A':
            case 'Z':
                return state.isCharacterMode() ? state.remainingCodePoints() : state.remainingBytes();
            case 'b':
            case 'B':
            case '%':
                return state.isCharacterMode() ? state.remainingCodePoints() * 8 : state.remainingBytes() * 8;
            case 'h':
            case 'H':
                return state.isCharacterMode() ? state.remainingCodePoints() * 2 : state.remainingBytes() * 2;
            default:
                FormatHandler handler = handlers.get(format);
                if (handler != null) {
                    int size = handler.getFormatSize();
                    if (size > 0) {
                        return state.remainingBytes() / size;
                    }
                }
                return Integer.MAX_VALUE; // Let the handler decide
        }
    }

    private static int findMatchingParen(String template, int openPos) {
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

    private static boolean hasConflictingEndianness(String groupContent, char groupEndian) {
        // Check if the group content has endianness modifiers that conflict with the group's endianness
        for (int i = 0; i < groupContent.length(); i++) {
            char c = groupContent.charAt(i);
            if ((c == '<' && groupEndian == '>') || (c == '>' && groupEndian == '<')) {
                // Check if this is actually a modifier (follows a format that supports it)
                if (i > 0) {
                    char prevChar = groupContent.charAt(i - 1);
                    if ("sSiIlLqQjJfFdDpP".indexOf(prevChar) >= 0) {
                        return true;
                    }
                }
            }
            // Also check for nested groups with conflicting endianness
            if (c == '(') {
                int closePos = findMatchingParen(groupContent, i);
                if (closePos != -1 && closePos + 1 < groupContent.length()) {
                    char nestedEndian = groupContent.charAt(closePos + 1);
                    if ((nestedEndian == '<' && groupEndian == '>') ||
                        (nestedEndian == '>' && groupEndian == '<')) {
                        return true;
                    }
                }
                i = closePos; // Skip the nested group
            }
        }
        return false;
    }

    private static void processGroup(String groupTemplate, UnpackState state, List<RuntimeBase> values,
                                     int repeatCount, boolean startsWithU, Stack<Boolean> modeStack) {
        // Save current mode
        boolean savedMode = state.isCharacterMode();

        for (int rep = 0; rep < repeatCount; rep++) {
            // If we're at the end of data and not on first iteration, stop
            if (rep > 0 && state.remainingBytes() == 0) {
                break;
            }

            // Process the group content
            for (int j = 0; j < groupTemplate.length(); j++) {
                char format = groupTemplate.charAt(j);

                // Skip whitespace
                if (Character.isWhitespace(format)) {
                    continue;
                }

                // Check for mode modifiers
                if (format == 'C' && j + 1 < groupTemplate.length() && groupTemplate.charAt(j + 1) == '0') {
                    state.switchToCharacterMode();
                    j++; // Skip the '0'
                    continue;
                } else if (format == 'U' && j + 1 < groupTemplate.length() && groupTemplate.charAt(j + 1) == '0') {
                    state.switchToByteMode();
                    j++; // Skip the '0'
                    continue;
                }

                // Handle '/' for counted strings
                if (format == '/') {
                    if (values.isEmpty()) {
                        throw new PerlCompilerException("'/' must follow a numeric type");
                    }

                    // Get the count from the last unpacked value
                    RuntimeBase lastValue = values.get(values.size() - 1);
                    int slashCount = ((RuntimeScalar) lastValue).getInt();
                    values.remove(values.size() - 1); // Remove the count value

                    // Get the string format that follows '/'
                    j++;
                    if (j >= groupTemplate.length()) {
                        throw new PerlCompilerException("Code missing after '/'");
                    }
                    char stringFormat = groupTemplate.charAt(j);

                    if (stringFormat != 'a' && stringFormat != 'A' && stringFormat != 'Z') {
                        throw new PerlCompilerException("'/' must be followed by a string type");
                    }

                    // Parse optional count/star after string format
                    boolean hasStarAfterSlash = false;
                    if (j + 1 < groupTemplate.length() && groupTemplate.charAt(j + 1) == '*') {
                        hasStarAfterSlash = true;
                        j++;
                    }

                    // Unpack the string with the count
                    FormatHandler stringHandler = handlers.get(stringFormat);
                    stringHandler.unpack(state, values, hasStarAfterSlash ? slashCount : 1, false);
                    continue;
                }

                // Parse count
                int count = 1;
                boolean isStarCount = false;

                if (j + 1 < groupTemplate.length()) {
                    char nextChar = groupTemplate.charAt(j + 1);
                    if (nextChar == '*') {
                        isStarCount = true;
                        j++;
                        count = getRemainingCount(state, format, startsWithU);
                    } else if (Character.isDigit(nextChar)) {
                        int k = j + 1;
                        while (k < groupTemplate.length() && Character.isDigit(groupTemplate.charAt(k))) {
                            k++;
                        }
                        count = Integer.parseInt(groupTemplate.substring(j + 1, k));
                        j = k - 1;
                    }
                }

                // Get handler and unpack
                FormatHandler handler = getHandler(format, startsWithU);
                if (handler != null) {
                    handler.unpack(state, values, count, isStarCount);
                } else {
                    throw new PerlCompilerException("unpack: unsupported format character: " + format);
                }
            }
        }

        // Restore mode
        if (savedMode) {
            state.switchToCharacterMode();
        } else {
            state.switchToByteMode();
        }
    }
}
