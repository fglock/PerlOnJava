package org.perlonjava.operators;

import org.perlonjava.operators.unpack.*;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

/**
 * Provides functionality to unpack binary data into a list of scalars
 * based on a specified template, similar to Perl's unpack function.
 */
public class Unpack {
    private static final Map<Character, FormatHandler> handlers = new HashMap<>();

    static {
        // Initialize format handlers
        handlers.put('c', new CFormatHandler());
        handlers.put('C', new CFormatHandler());
        handlers.put('S', new NumericFormatHandler.ShortHandler(false));
        handlers.put('s', new NumericFormatHandler.ShortHandler(true));
        handlers.put('L', new NumericFormatHandler.LongHandler(false));
        handlers.put('l', new NumericFormatHandler.LongHandler(true));
        handlers.put('i', new NumericFormatHandler.LongHandler(true));
        handlers.put('I', new NumericFormatHandler.LongHandler(true));
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
        // Note: U handler is created dynamically based on startsWithU
    }

    public static RuntimeList unpack(RuntimeList args) {
        if (args.elements.size() < 2) {
            throw new PerlCompilerException("unpack: not enough arguments");
        }

        RuntimeScalar templateScalar = (RuntimeScalar) args.elements.get(0);
        RuntimeScalar packedData = args.elements.get(1).scalar();

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

            // Handle parentheses for grouping
            if (format == '(') {
                // Find matching closing parenthesis
                int closePos = findMatchingParen(template, i);
                if (closePos == -1) {
                    throw new PerlCompilerException("unpack: unmatched parenthesis in template");
                }

                // Extract group content
                String groupContent = template.substring(i + 1, closePos);

                // Check for repeat count after closing paren
                int groupRepeatCount = 1;
                int nextPos = closePos + 1;

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
                    count = Integer.parseInt(template.substring(i + 1, j));
                    i = j - 1;
                }
            }

            // Get handler and unpack
            FormatHandler handler = getHandler(format, startsWithU);
            if (handler != null) {
                handler.unpack(state, values, count, isStarCount);
            } else {
                throw new PerlCompilerException("unpack: unsupported format character: " + format);
            }

            i++;
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
