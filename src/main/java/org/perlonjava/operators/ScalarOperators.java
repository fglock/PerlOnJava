package org.perlonjava.operators;

import org.perlonjava.parser.StringParser;
import org.perlonjava.runtime.RuntimeScalar;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarZero;

public class ScalarOperators {
    public static RuntimeScalar oct(RuntimeScalar runtimeScalar) {
        String expr = runtimeScalar.toString();

        StringParser.assertNoWideCharacters(expr, "oct");

        long result = 0;

        // Remove leading and trailing whitespace
        expr = expr.trim();

        // Remove underscores as they are ignored in Perl's oct()
        expr = expr.replace("_", "");

        int length = expr.length();
        int start = 0;
        if (expr.startsWith("0")) {
            start++;
        }

        if (expr.charAt(start) == 'x' || expr.charAt(start) == 'X') {
            // Hexadecimal string
            start++;
            for (int i = start; i < length; i++) {
                char c = expr.charAt(i);
                int digit = Character.digit(c, 16); // Converts '0'-'9', 'A'-'F', 'a'-'f' to 0-15

                // Stop if an invalid character is encountered
                if (digit == -1) {
                    break;
                }
                result = result * 16 + digit;
            }
        } else if (expr.charAt(start) == 'b' || expr.charAt(start) == 'B') {
            // Binary string
            start++;
            for (int i = start; i < length; i++) {
                char c = expr.charAt(i);
                if (c < '0' || c > '1') {
                    break;
                }
                result = result * 2 + (c - '0');
            }
        } else {
            // Octal string
            if (expr.charAt(start) == 'o' || expr.charAt(start) == 'O') {
                start++;
            }
            for (int i = start; i < length; i++) {
                char c = expr.charAt(i);
                if (c < '0' || c > '7') {
                    break;
                }
                result = result * 8 + (c - '0');
            }
        }
        return getScalarInt(result);
    }

    public static RuntimeScalar ord(RuntimeScalar runtimeScalar) {
        String str = runtimeScalar.toString();
        int i;
        if (str.isEmpty()) {
            i = 0;
        } else {
            i = str.codePointAt(0);
        }
        return getScalarInt(i);
    }

    /**
     * Returns the numeric value of the first byte when 'use bytes' pragma is in effect.
     * This treats the string as a sequence of bytes rather than characters.
     *
     * @param runtimeScalar the RuntimeScalar whose first byte value is to be returned
     * @return a RuntimeScalar containing the byte value (0-255)
     */
    public static RuntimeScalar ordBytes(RuntimeScalar runtimeScalar) {
        String str = runtimeScalar.toString();
        int i;
        if (str.isEmpty()) {
            i = 0;
        } else {
            // Get the first byte of the UTF-8 representation
            try {
                byte[] bytes = str.getBytes("UTF-8");
                if (bytes.length > 0) {
                    i = bytes[0] & 0xFF;  // Convert to unsigned
                } else {
                    i = 0;
                }
            } catch (Exception e) {
                // If UTF-8 encoding fails, fall back to first character
                i = str.charAt(0);
            }
        }
        return getScalarInt(i);
    }

    public static RuntimeScalar hex(RuntimeScalar runtimeScalar) {
        String expr = runtimeScalar.toString();
        long result = 0;

        StringParser.assertNoWideCharacters(expr, "hex");

        // Remove underscores as they are ignored in Perl's hex()
        expr = expr.replace("_", "");

        int len = expr.length();

        int start = 0;
        if (expr.startsWith("0")) {
            start++;
        }
        if (start >= len) {
            return scalarZero;
        }
        if (start < len && (expr.charAt(start) == 'x' || expr.charAt(start) == 'X')) {
            start++;
        }
        // Convert each valid hex character
        for (int i = start; i < expr.length(); i++) {
            char c = expr.charAt(i);
            int digit = Character.digit(c, 16); // Converts '0'-'9', 'A'-'F', 'a'-'f' to 0-15

            // Stop if an invalid character is encountered
            if (digit == -1) {
                break;
            }

            result = result * 16 + digit;
        }
        return getScalarInt(result);
    }
}
