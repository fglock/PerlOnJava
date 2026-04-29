package org.perlonjava.runtime.operators;

import org.perlonjava.frontend.parser.StringParser;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.charset.StandardCharsets;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarZero;

public class ScalarOperators {
    public static RuntimeScalar oct(RuntimeScalar runtimeScalar) {
        String expr = runtimeScalar.toString();

        StringParser.assertNoWideCharacters(expr, "oct");

        long result = 0;
        boolean useDouble = false;
        double doubleResult = 0.0;

        // Remove leading and trailing whitespace
        expr = expr.trim();

        // Remove underscores as they are ignored in Perl's oct()
        expr = expr.replace("_", "");

        int length = expr.length();
        
        // Handle empty string or just "0"
        if (length == 0) {
            return scalarZero;
        }
        
        int start = 0;
        if (expr.startsWith("0")) {
            start++;
        }

        // Check if we've consumed the entire string (e.g., input was just "0")
        if (start >= length) {
            return scalarZero;
        }

        if (expr.charAt(start) == 'x' || expr.charAt(start) == 'X') {
            // Hexadecimal string
            start++;
            for (int i = start; i < length; i++) {
                char c = expr.charAt(i);
                int digit = Character.digit(c, 16);
                if (digit == -1) break;
                if (!useDouble) {
                    if (Long.compareUnsigned(result, Long.divideUnsigned(-1L, 16)) > 0) {
                        useDouble = true;
                        doubleResult = unsignedLongToDouble(result) * 16 + digit;
                    } else {
                        result = result * 16 + digit;
                    }
                } else {
                    doubleResult = doubleResult * 16 + digit;
                }
            }
        } else if (expr.charAt(start) == 'b' || expr.charAt(start) == 'B') {
            // Binary string
            start++;
            for (int i = start; i < length; i++) {
                char c = expr.charAt(i);
                if (c < '0' || c > '1') break;
                if (!useDouble) {
                    if (Long.compareUnsigned(result, Long.divideUnsigned(-1L, 2)) > 0) {
                        useDouble = true;
                        doubleResult = unsignedLongToDouble(result) * 2 + (c - '0');
                    } else {
                        result = result * 2 + (c - '0');
                    }
                } else {
                    doubleResult = doubleResult * 2 + (c - '0');
                }
            }
        } else {
            // Octal string
            if (expr.charAt(start) == 'o' || expr.charAt(start) == 'O') {
                start++;
            }
            for (int i = start; i < length; i++) {
                char c = expr.charAt(i);
                if (c < '0' || c > '7') break;
                if (!useDouble) {
                    if (Long.compareUnsigned(result, Long.divideUnsigned(-1L, 8)) > 0) {
                        useDouble = true;
                        doubleResult = unsignedLongToDouble(result) * 8 + (c - '0');
                    } else {
                        result = result * 8 + (c - '0');
                    }
                } else {
                    doubleResult = doubleResult * 8 + (c - '0');
                }
            }
        }
        if (useDouble) {
            return new RuntimeScalar(doubleResult);
        }
        // If result is negative as signed long, it represents an unsigned value >= 2^63
        // Return as double since Java doesn't have unsigned long type
        if (result < 0) {
            return new RuntimeScalar(unsignedLongToDouble(result));
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
        } else if (runtimeScalar.type == org.perlonjava.runtime.runtimetypes.RuntimeScalarType.BYTE_STRING) {
            // BYTE_STRING already stores raw bytes (each char is a byte 0-255).
            // Returning the first char value avoids re-encoding to UTF-8, which
            // would bytes::ord(chr(0x84)) yield 0xc2 instead of 0x84.
            i = str.charAt(0) & 0xFF;
        } else {
            // Get the first byte of the UTF-8 representation
            try {
                byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
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
        boolean useDouble = false;
        double doubleResult = 0.0;

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
            int digit = Character.digit(c, 16);
            if (digit == -1) break;
            if (!useDouble) {
                if (Long.compareUnsigned(result, Long.divideUnsigned(-1L, 16)) > 0) {
                    useDouble = true;
                    doubleResult = unsignedLongToDouble(result) * 16 + digit;
                } else {
                    result = result * 16 + digit;
                }
            } else {
                doubleResult = doubleResult * 16 + digit;
            }
        }
        if (useDouble) {
            return new RuntimeScalar(doubleResult);
        }
        if (result < 0) {
            return new RuntimeScalar(unsignedLongToDouble(result));
        }
        return getScalarInt(result);
    }

    /**
     * Converts an unsigned long value to double.
     * Handles the case where the long is negative in signed representation
     * but represents a large unsigned value.
     */
    private static double unsignedLongToDouble(long value) {
        if (value >= 0) return (double) value;
        // For negative signed longs (large unsigned values):
        // Split into upper and lower halves to avoid precision loss
        return (double) (value >>> 1) * 2.0 + (value & 1);
    }
}
