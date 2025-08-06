package org.perlonjava.operators;

import org.perlonjava.operators.unpack.*;
import org.perlonjava.runtime.*;
import java.util.*;

/**
 * Provides functionality to unpack binary data into a list of scalars
 * based on a specified template, similar to Perl's unpack function.
 */
public class Unpack {
    private static final Map<Character, FormatHandler> handlers = new HashMap<>();

    static {
        // Initialize format handlers
        handlers.put('C', new CFormatHandler());
        handlers.put('S', new NumericFormatHandler.ShortHandler(false));
        handlers.put('s', new NumericFormatHandler.ShortHandler(true));
        handlers.put('L', new NumericFormatHandler.LongHandler(false));
        handlers.put('l', new NumericFormatHandler.LongHandler(true));
        handlers.put('N', new NumericFormatHandler.NetworkLongHandler());
        handlers.put('n', new NumericFormatHandler.NetworkShortHandler());
        handlers.put('V', new NumericFormatHandler.VAXLongHandler());
        handlers.put('v', new NumericFormatHandler.VAXShortHandler());
        handlers.put('f', new NumericFormatHandler.FloatHandler());
        handlers.put('d', new NumericFormatHandler.DoubleHandler());
        handlers.put('a', new StringFormatHandler('a'));
        handlers.put('A', new StringFormatHandler('A'));
        handlers.put('Z', new StringFormatHandler('Z'));
        handlers.put('b', new BitStringFormatHandler('b'));
        handlers.put('B', new BitStringFormatHandler('B'));
        handlers.put('h', new HexStringFormatHandler('h'));
        handlers.put('H', new HexStringFormatHandler('H'));
        handlers.put('W', new WFormatHandler());
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
        for (int i = 0; i < template.length(); i++) {
            char format = template.charAt(i);

            // Handle parentheses for mode scoping
            if (format == '(') {
                // Push current mode onto stack
                modeStack.push(state.isCharacterMode());
                continue;
            } else if (format == ')') {
                // Restore mode from stack
                if (!modeStack.isEmpty()) {
                    boolean savedMode = modeStack.pop();
                    if (savedMode) {
                        state.switchToCharacterMode();
                    } else {
                        state.switchToByteMode();
                    }
                }
                continue;
            }

            // Skip whitespace
            if (Character.isWhitespace(format)) {
                continue;
            }

            // Check for explicit mode modifiers
            if (format == 'C' && i + 1 < template.length() && template.charAt(i + 1) == '0') {
                state.switchToCharacterMode();
                i++; // Skip the '0'
                continue;
            } else if (format == 'U' && i + 1 < template.length() && template.charAt(i + 1) == '0') {
                state.switchToByteMode();
                i++; // Skip the '0'
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
}