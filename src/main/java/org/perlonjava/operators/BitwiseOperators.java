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
     * Helper method for performing a bitwise AND operation on two strings.
     *
     * @param s1 The first string operand.
     * @param s2 The second string operand.
     * @return The result of the bitwise AND operation as a string.
     * @throws PerlCompilerException if the strings contain characters with code points over 0xFF.
     */
    private static String bitwiseAndStrings(String s1, String s2) {
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

        return result.toString();
    }

    /**
     * Helper method for performing a bitwise OR operation on two strings.
     *
     * @param s1 The first string operand.
     * @param s2 The second string operand.
     * @return The result of the bitwise OR operation as a string.
     * @throws PerlCompilerException if the strings contain characters with code points over 0xFF.
     */
    private static String bitwiseOrStrings(String s1, String s2) {
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

        return result.toString();
    }

    /**
     * Helper method for performing a bitwise XOR operation on two strings.
     *
     * @param s1 The first string operand.
     * @param s2 The second string operand.
     * @return The result of the bitwise XOR operation as a string.
     * @throws PerlCompilerException if the strings contain characters with code points over 0xFF.
     */
    private static String bitwiseXorStrings(String s1, String s2) {
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

        return result.toString();
    }

    /**
     * Helper method for performing a bitwise NOT operation on a string.
     *
     * @param s The string operand.
     * @return The result of the bitwise NOT operation as a string.
     * @throws PerlCompilerException if the string contains characters with code points over 0xFF.
     */
    private static String bitwiseNotString(String s) {
        StringBuilder result = new StringBuilder(s.length());

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0xFF) {
                throw new PerlCompilerException("Use of strings with code points over 0xFF as arguments to bitwise not (~) operator is not allowed");
            }
            result.append((char) ((~c) & 0xFF));
        }

        return result.toString();
    }

    /**
     * Performs a bitwise AND operation on two RuntimeScalar objects.
     * If both arguments are strings, it performs the operation character by character.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise AND operation.
     */
    public static RuntimeScalar bitwiseAnd(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        if (runtimeScalar.type == RuntimeScalarType.STRING && arg2.type == RuntimeScalarType.STRING) {
            return new RuntimeScalar(bitwiseAndStrings(runtimeScalar.toString(), arg2.toString()));
        }
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
        if (runtimeScalar.type == RuntimeScalarType.STRING && arg2.type == RuntimeScalarType.STRING) {
            return new RuntimeScalar(bitwiseOrStrings(runtimeScalar.toString(), arg2.toString()));
        }
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
        if (runtimeScalar.type == RuntimeScalarType.STRING && arg2.type == RuntimeScalarType.STRING) {
            return new RuntimeScalar(bitwiseXorStrings(runtimeScalar.toString(), arg2.toString()));
        }
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
        if (runtimeScalar.type == RuntimeScalarType.STRING) {
            return new RuntimeScalar(bitwiseNotString(runtimeScalar.toString()));
        }
        return new RuntimeScalar(~runtimeScalar.getLong() & 0xFFFFFFFFL);
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
        return new RuntimeScalar(bitwiseAndStrings(runtimeScalar.toString(), arg2.toString()));
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
        return new RuntimeScalar(bitwiseOrStrings(runtimeScalar.toString(), arg2.toString()));
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
        return new RuntimeScalar(bitwiseXorStrings(runtimeScalar.toString(), arg2.toString()));
    }

    /**
     * Performs a bitwise NOT operation on a single string RuntimeScalar object.
     * This simulates the Perl bitwise string operator ~.
     *
     * @param runtimeScalar The operand.
     * @return A new RuntimeScalar with the result of the bitwise NOT operation on the string.
     */
    public static RuntimeScalar bitwiseNotDot(RuntimeScalar runtimeScalar) {
        return new RuntimeScalar(bitwiseNotString(runtimeScalar.toString()));
    }

    /**
     * Performs a left shift operation on a RuntimeScalar object.
     *
     * @param runtimeScalar The operand to be shifted.
     * @param arg2          The number of positions to shift.
     * @return A new RuntimeScalar with the result of the left shift operation.
     */
    public static RuntimeScalar shiftLeft(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        return new RuntimeScalar(runtimeScalar.getInt() << arg2.getInt());
    }

    /**
     * Performs a right shift operation on a RuntimeScalar object.
     *
     * @param runtimeScalar The operand to be shifted.
     * @param arg2          The number of positions to shift.
     * @return A new RuntimeScalar with the result of the right shift operation.
     */
    public static RuntimeScalar shiftRight(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        return new RuntimeScalar(runtimeScalar.getInt() >> arg2.getInt());
    }
}
