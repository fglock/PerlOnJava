package org.perlonjava.operators;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarType;

/**
 * This class provides methods for performing bitwise operations on RuntimeScalar objects.
 * It supports operations for both numeric and string types, with specific behavior for each.
 * Additionally, it implements Perl-like bitwise string operators.
 */
public class BitwiseOperators {

    /**
     * Performs a bitwise AND operation on two RuntimeScalar objects.
     * If both arguments are strings, it performs the operation character by character.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise AND operation.
     */
    public static RuntimeScalar bitwiseAnd(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        if (runtimeScalar.isString() && arg2.isString()) {
            return bitwiseAndDot(runtimeScalar, arg2);
        }
        return bitwiseAndBinary(runtimeScalar, arg2);
    }

    /**
     * Performs a bitwise AND operation on two numeric RuntimeScalar objects.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise AND operation.
     */
    public static RuntimeScalar bitwiseAndBinary(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        return new RuntimeScalar(runtimeScalar.getLong() & arg2.getLong());
    }

    /**
     * Performs a bitwise OR operation on two RuntimeScalar objects.
     * If both arguments are strings, it performs the operation character by character.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise OR operation.
     */
    public static RuntimeScalar bitwiseOr(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        if (runtimeScalar.isString() && arg2.isString()) {
            return bitwiseOrDot(runtimeScalar, arg2);
        }
        return bitwiseOrBinary(runtimeScalar, arg2);
    }

    /**
     * Performs a bitwise OR operation on two numeric RuntimeScalar objects.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise OR operation.
     */
    public static RuntimeScalar bitwiseOrBinary(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        return new RuntimeScalar(runtimeScalar.getLong() | arg2.getLong());
    }

    /**
     * Performs a bitwise XOR operation on two RuntimeScalar objects.
     * If both arguments are strings, it performs the operation character by character.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise XOR operation.
     */
    public static RuntimeScalar bitwiseXor(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        if (runtimeScalar.isString() && arg2.isString()) {
            return bitwiseXorDot(runtimeScalar, arg2);
        }
        return bitwiseXorBinary(runtimeScalar, arg2);
    }

    /**
     * Performs a bitwise XOR operation on two numeric RuntimeScalar objects.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise XOR operation.
     */
    public static RuntimeScalar bitwiseXorBinary(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        return new RuntimeScalar(runtimeScalar.getLong() ^ arg2.getLong());
    }

    /**
     * Performs a bitwise NOT operation on a RuntimeScalar object.
     * If the argument is a string, it performs the operation character by character.
     *
     * @param runtimeScalar The operand.
     * @return A new RuntimeScalar with the result of the bitwise NOT operation.
     */
    public static RuntimeScalar bitwiseNot(RuntimeScalar runtimeScalar) {
        if (runtimeScalar.isString()) {
            return bitwiseNotDot(runtimeScalar);
        }
        return bitwiseNotBinary(runtimeScalar);
    }

    /**
     * Performs a bitwise NOT operation on a numeric RuntimeScalar object.
     *
     * @param runtimeScalar The operand.
     * @return A new RuntimeScalar with the result of the bitwise NOT operation.
     */
    public static RuntimeScalar bitwiseNotBinary(RuntimeScalar runtimeScalar) {
        long value = runtimeScalar.getLong();

        // Perl uses 32-bit semantics for bitwise operations by default
        // First cast to int to get 32-bit value, then apply NOT
        int intValue = (int) value;
        int intResult = ~intValue;

        // Convert back to long, preserving sign extension
        long result = intResult;

        // If the result is negative, convert to unsigned 32-bit representation
        if (result < 0) {
            // Add 2^32 to convert to unsigned 32-bit value
            result = result + 4294967296L; // 2^32
        }

        return new RuntimeScalar(result);
    }

    /**
     * Performs a bitwise AND operation on a single string RuntimeScalar object.
     * This simulates the Perl bitwise string operator &.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise AND operation on the string.
     */
    public static RuntimeScalar bitwiseAndDot(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        String s1 = runtimeScalar.toString();
        String s2 = arg2.toString();
        int len = Math.min(s1.length(), s2.length());
        StringBuilder result = new StringBuilder(len);

        for (int i = 0; i < len; i++) {
            char c1 = s1.charAt(i);
            char c2 = s2.charAt(i);
            if (c1 > 0xFF || c2 > 0xFF) {
                throw new PerlCompilerException("Use of strings with code points over 0xFF as arguments to bitwise and (&) operator is not allowed");
            }
            result.append((char) (c1 & c2));
        }

        return new RuntimeScalar(result.toString());
    }

    /**
     * Performs a bitwise OR operation on a single string RuntimeScalar object.
     * This simulates the Perl bitwise string operator |.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise OR operation on the string.
     */
    public static RuntimeScalar bitwiseOrDot(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        String s1 = runtimeScalar.toString();
        String s2 = arg2.toString();
        int len = Math.max(s1.length(), s2.length());
        StringBuilder result = new StringBuilder(len);

        for (int i = 0; i < len; i++) {
            char c1 = i < s1.length() ? s1.charAt(i) : 0;
            char c2 = i < s2.length() ? s2.charAt(i) : 0;
            if (c1 > 0xFF || c2 > 0xFF) {
                throw new PerlCompilerException("Use of strings with code points over 0xFF as arguments to bitwise or (|) operator is not allowed");
            }
            result.append((char) (c1 | c2));
        }

        return new RuntimeScalar(result.toString());
    }

    /**
     * Performs a bitwise XOR operation on a single string RuntimeScalar object.
     * This simulates the Perl bitwise string operator ^.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise XOR operation on the string.
     */
    public static RuntimeScalar bitwiseXorDot(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        String s1 = runtimeScalar.toString();
        String s2 = arg2.toString();
        int len = Math.max(s1.length(), s2.length());
        StringBuilder result = new StringBuilder(len);

        for (int i = 0; i < len; i++) {
            char c1 = i < s1.length() ? s1.charAt(i) : 0;
            char c2 = i < s2.length() ? s2.charAt(i) : 0;
            if (c1 > 0xFF || c2 > 0xFF) {
                throw new PerlCompilerException("Use of strings with code points over 0xFF as arguments to bitwise xor (^) operator is not allowed");
            }
            result.append((char) (c1 ^ c2));
        }

        return new RuntimeScalar(result.toString());
    }

    /**
     * Performs a bitwise NOT operation on a single string RuntimeScalar object.
     * This simulates the Perl bitwise string operator ~.
     *
     * @param runtimeScalar The operand.
     * @return A new RuntimeScalar with the result of the bitwise NOT operation on the string.
     */
    public static RuntimeScalar bitwiseNotDot(RuntimeScalar runtimeScalar) {
        String s = runtimeScalar.toString();
        StringBuilder result = new StringBuilder(s.length());

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0xFF) {
                throw new PerlCompilerException("Use of strings with code points over 0xFF as arguments to bitwise not (~) operator is not allowed");
            }
            result.append((char) ((~c) & 0xFF));
        }

        return new RuntimeScalar(result.toString());
    }

    /**
     * Performs a left shift operation on a RuntimeScalar object.
     *
     * @param runtimeScalar The operand to be shifted.
     * @param arg2          The number of positions to shift.
     * @return A new RuntimeScalar with the result of the left shift operation.
     */
    public static RuntimeScalar shiftLeft(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        long value = runtimeScalar.getLong();
        int shift = arg2.getInt();

        // For shifts that would overflow long, use double to match pow() behavior
        if (shift >= 63) {
            if (shift >= 1024) {
                // Prevent infinity for extremely large shifts
                return new RuntimeScalar(0.0);
            }
            if (value == 0) {
                return new RuntimeScalar(0L);
            }
            // Use double arithmetic for large shifts to match pow() behavior
            double result = value * Math.pow(2.0, shift);
            return new RuntimeScalar(result);
        }

        // For smaller shifts, check if the result would overflow
        if (shift > 0) {
            // Check if the shift would cause overflow
            long result = value << shift;
            // If sign changed unexpectedly (overflow), use double
            if ((value > 0 && result < 0) || (value < 0 && result > 0)) {
                double doubleResult = value * Math.pow(2.0, shift);
                return new RuntimeScalar(doubleResult);
            }
            return new RuntimeScalar(result);
        }

        return new RuntimeScalar(value << shift);
    }

    /**
     * Performs a right shift operation on a RuntimeScalar object.
     *
     * @param runtimeScalar The operand to be shifted.
     * @param arg2          The number of positions to shift.
     * @return A new RuntimeScalar with the result of the right shift operation.
     */
    public static RuntimeScalar shiftRight(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        // Use long for consistency
        long value = runtimeScalar.getLong();
        int shift = arg2.getInt();

        // Handle shifts >= 64 (long size in bits)
        if (shift >= 64) {
            return new RuntimeScalar(value < 0 ? -1L : 0L);
        }

        return new RuntimeScalar(value >> shift);
    }
}
