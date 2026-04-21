package org.perlonjava.runtime.operators;

import org.perlonjava.frontend.parser.NumberParser;
import org.perlonjava.runtime.runtimetypes.*;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.blessedId;

/**
 * This class provides methods for performing bitwise operations on RuntimeScalar objects.
 * It supports operations for both numeric and string types, with specific behavior for each.
 * Additionally, it implements Perl-like bitwise string operators.
 */
public class BitwiseOperators {

    /**
     * Performs a bitwise AND operation on two RuntimeScalar objects.
     * If both arguments are strings, it performs the operation character by character.
     * In Perl, references and non-numeric values are stringified before bitwise operations.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise AND operation.
     */
    public static RuntimeScalar bitwiseAnd(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        // Fast path: both INTEGER - skip all checks (defined, looksLikeNumber, tied)
        int t1 = runtimeScalar.type;
        int t2 = arg2.type;
        if (t1 == RuntimeScalarType.INTEGER && t2 == RuntimeScalarType.INTEGER) {
            int result = ((int) runtimeScalar.value) & ((int) arg2.value);
            return new RuntimeScalar(result);
        }

        // Check for overloaded '&' operator on blessed objects
        int blessId = blessedId(runtimeScalar);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(&", "&");
            if (result != null) return result;
        }

        // Fetch tied/readonly scalars once to avoid redundant FETCH calls
        RuntimeScalar val1 = t1 < RuntimeScalarType.TIED_SCALAR ? runtimeScalar :
                t1 == RuntimeScalarType.TIED_SCALAR ? runtimeScalar.tiedFetch() :
                        t1 == RuntimeScalarType.READONLY_SCALAR ? (RuntimeScalar) runtimeScalar.value : runtimeScalar;
        RuntimeScalar val2 = t2 < RuntimeScalarType.TIED_SCALAR ? arg2 :
                t2 == RuntimeScalarType.TIED_SCALAR ? arg2.tiedFetch() :
                        t2 == RuntimeScalarType.READONLY_SCALAR ? (RuntimeScalar) arg2.value : arg2;

        // Check for uninitialized values and generate warnings
        if (!val1.getDefinedBoolean()) {
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in bitwise and (&)"),
                    RuntimeScalarCache.scalarEmptyString, "uninitialized");
        }
        if (!val2.getDefinedBoolean()) {
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in bitwise and (&)"),
                    RuntimeScalarCache.scalarEmptyString, "uninitialized");
        }

        // In Perl, if either operand is a reference or doesn't look like a number, use string operations
        if (!ScalarUtils.looksLikeNumber(val1) || !ScalarUtils.looksLikeNumber(val2)) {
            return bitwiseAndDot(val1, val2);
        }
        return bitwiseAndBinary(val1, val2);
    }

    /**
     * Performs a bitwise AND operation on two numeric RuntimeScalar objects.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise AND operation.
     */
    public static RuntimeScalar bitwiseAndBinary(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        // Use long values to preserve full precision
        long val1 = runtimeScalar.getLong();
        long val2 = arg2.getLong();

        // Perform AND operation preserving all bits
        long result = val1 & val2;

        return new RuntimeScalar(result);
    }

    /**
     * Performs a bitwise OR operation on two RuntimeScalar objects.
     * If both arguments are strings, it performs the operation character by character.
     * In Perl, references and non-numeric values are stringified before bitwise operations.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise OR operation.
     */
    public static RuntimeScalar bitwiseOr(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        // Fast path: both INTEGER - skip all checks (looksLikeNumber, tied)
        int t1 = runtimeScalar.type;
        int t2 = arg2.type;
        if (t1 == RuntimeScalarType.INTEGER && t2 == RuntimeScalarType.INTEGER) {
            int result = ((int) runtimeScalar.value) | ((int) arg2.value);
            return new RuntimeScalar(result);
        }

        // Check for overloaded '|' operator on blessed objects
        int blessId = blessedId(runtimeScalar);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(|", "|");
            if (result != null) return result;
        }

        // Fetch tied/readonly scalars once to avoid redundant FETCH calls
        RuntimeScalar val1 = t1 < RuntimeScalarType.TIED_SCALAR ? runtimeScalar :
                t1 == RuntimeScalarType.TIED_SCALAR ? runtimeScalar.tiedFetch() :
                        t1 == RuntimeScalarType.READONLY_SCALAR ? (RuntimeScalar) runtimeScalar.value : runtimeScalar;
        RuntimeScalar val2 = t2 < RuntimeScalarType.TIED_SCALAR ? arg2 :
                t2 == RuntimeScalarType.TIED_SCALAR ? arg2.tiedFetch() :
                        t2 == RuntimeScalarType.READONLY_SCALAR ? (RuntimeScalar) arg2.value : arg2;

        // In Perl, if either operand is a reference or doesn't look like a number, use string operations
        if (!ScalarUtils.looksLikeNumber(val1) || !ScalarUtils.looksLikeNumber(val2)) {
            return bitwiseOrDot(val1, val2);
        }
        return bitwiseOrBinary(val1, val2);
    }

    /**
     * Performs a bitwise OR operation on two numeric RuntimeScalar objects.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise OR operation.
     */
    public static RuntimeScalar bitwiseOrBinary(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        // Use long values to preserve full precision
        long val1 = runtimeScalar.getLong();
        long val2 = arg2.getLong();

        // Perform OR operation preserving all bits
        long result = val1 | val2;

        return new RuntimeScalar(result);
    }

    /**
     * Performs a bitwise XOR operation on two RuntimeScalar objects.
     * <p>
     * Perl's XOR behavior:
     * - If both operands are pure numeric types (INTEGER/DOUBLE), use numeric XOR
     * - Otherwise (strings, blessed objects, references, etc.), use string XOR
     * <p>
     * Note: References and non-numeric values are stringified before bitwise operations.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise XOR operation.
     */
    public static RuntimeScalar bitwiseXor(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        // Fast path: both INTEGER - skip all checks (looksLikeNumber, tied)
        int t1 = runtimeScalar.type;
        int t2 = arg2.type;
        if (t1 == RuntimeScalarType.INTEGER && t2 == RuntimeScalarType.INTEGER) {
            // int ^ int produces int; call RuntimeScalar(int) directly to skip
            // initializeWithLong's range-check branches (JFR hot path).
            return new RuntimeScalar(((int) runtimeScalar.value) ^ ((int) arg2.value));
        }

        // Check for overloaded '^' operator on blessed objects
        int blessId = blessedId(runtimeScalar);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(^", "^");
            if (result != null) return result;
        }

        // Fetch tied/readonly scalars once to avoid redundant FETCH calls
        RuntimeScalar val1 = t1 < RuntimeScalarType.TIED_SCALAR ? runtimeScalar :
                t1 == RuntimeScalarType.TIED_SCALAR ? runtimeScalar.tiedFetch() :
                        t1 == RuntimeScalarType.READONLY_SCALAR ? (RuntimeScalar) runtimeScalar.value : runtimeScalar;
        RuntimeScalar val2 = t2 < RuntimeScalarType.TIED_SCALAR ? arg2 :
                t2 == RuntimeScalarType.TIED_SCALAR ? arg2.tiedFetch() :
                        t2 == RuntimeScalarType.READONLY_SCALAR ? (RuntimeScalar) arg2.value : arg2;

        // Use numeric XOR only if BOTH operands look like numbers
        // For everything else (strings, blessed objects, references, etc.), use string XOR
        if (ScalarUtils.looksLikeNumber(val1) && ScalarUtils.looksLikeNumber(val2)) {
            // Both are pure numbers (INTEGER or DOUBLE), use numeric XOR
            return bitwiseXorBinary(val1, val2);
        }
        // At least one is a string, reference, or blessed object, use string XOR
        return bitwiseXorDot(val1, val2);
    }

    /**
     * Performs a bitwise XOR operation on two numeric RuntimeScalar objects.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise XOR operation.
     */
    public static RuntimeScalar bitwiseXorBinary(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        // Use long values to preserve full precision
        long val1 = runtimeScalar.getLong();
        long val2 = arg2.getLong();

        // Perform XOR operation preserving all bits
        long result = val1 ^ val2;

        return new RuntimeScalar(result);
    }

    /**
     * Performs a bitwise NOT operation on a RuntimeScalar object.
     * If the argument is a string, it performs the operation character by character.
     * In Perl, references and non-numeric values are stringified before bitwise operations.
     *
     * @param runtimeScalar The operand.
     * @return A new RuntimeScalar with the result of the bitwise NOT operation.
     */
    public static RuntimeScalar bitwiseNot(RuntimeScalar runtimeScalar) {
        // Check for overloaded '~' operator on blessed objects
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(
                    runtimeScalar, blessId, "(~", "~", BitwiseOperators::bitwiseNot);
            if (result != null) return result;
        }

        // Fetch tied/readonly scalar once to avoid redundant FETCH calls
        RuntimeScalar val = runtimeScalar.type < RuntimeScalarType.TIED_SCALAR ? runtimeScalar :
                runtimeScalar.type == RuntimeScalarType.TIED_SCALAR ? runtimeScalar.tiedFetch() :
                        runtimeScalar.type == RuntimeScalarType.READONLY_SCALAR ? (RuntimeScalar) runtimeScalar.value : runtimeScalar;

        // In Perl, if the operand is a reference or doesn't look like a number, use string operations
        if (!ScalarUtils.looksLikeNumber(val)) {
            return bitwiseNotDot(val);
        }
        return bitwiseNotBinary(val);
    }

    /**
     * Performs a bitwise NOT operation on a numeric RuntimeScalar object.
     * This method now properly handles 32-bit unsigned integer semantics like Perl.
     *
     * @param runtimeScalar The operand.
     * @return A new RuntimeScalar with the result of the bitwise NOT operation.
     */
    public static RuntimeScalar bitwiseNotBinary(RuntimeScalar runtimeScalar) {
        long value = runtimeScalar.getLong();

        // Perl uses 32-bit semantics for bitwise operations
        // Treat the input as an unsigned 32-bit value, then apply bitwise NOT

        // First, ensure we're working with a 32-bit value by masking
        long masked32bit = value & 0xFFFFFFFFL;

        // Apply bitwise NOT and mask to 32 bits
        long result = (~masked32bit) & 0xFFFFFFFFL;

        return new RuntimeScalar(result);
    }

    /**
     * Performs a bitwise NOT operation with signed (integer) semantics.
     * This is used when "use integer" pragma is in effect.
     *
     * @param runtimeScalar The operand.
     * @return A new RuntimeScalar with the result of the integer bitwise NOT operation.
     */
    public static RuntimeScalar integerBitwiseNot(RuntimeScalar runtimeScalar) {
        // Check for overloaded '~' operator on blessed objects
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(
                    runtimeScalar, blessId, "(~", "~", BitwiseOperators::integerBitwiseNot);
            if (result != null) return result;
        }

        // Fetch tied/readonly scalar once to avoid redundant FETCH calls
        RuntimeScalar val = runtimeScalar.type < RuntimeScalarType.TIED_SCALAR ? runtimeScalar :
                runtimeScalar.type == RuntimeScalarType.TIED_SCALAR ? runtimeScalar.tiedFetch() :
                        runtimeScalar.type == RuntimeScalarType.READONLY_SCALAR ? (RuntimeScalar) runtimeScalar.value : runtimeScalar;

        // In Perl, if the operand is a reference or doesn't look like a number, use string operations
        if (!ScalarUtils.looksLikeNumber(val)) {
            return bitwiseNotDot(val);
        }

        // Must use 32-bit int (not long) to match ivsize=4 in Config.pm.
        // Using long would make ~3 return -4 as a 64-bit value, breaking bop.t tests
        // that expect 32-bit signed integer semantics under "use integer".

        int value = (int) val.getLong();
        int result = ~value;
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
     * Perl shifts treat negative numbers as unsigned (UV) by default.
     * Negative shift amounts reverse the direction (left shift becomes right shift).
     *
     * @param runtimeScalar The operand to be shifted.
     * @param arg2          The number of positions to shift.
     * @return A new RuntimeScalar with the result of the left shift operation.
     */
    public static RuntimeScalar shiftLeft(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        // Fast path: both INTEGER with non-negative shift < 32
        int t1 = runtimeScalar.type;
        int t2 = arg2.type;
        if (t1 == RuntimeScalarType.INTEGER && t2 == RuntimeScalarType.INTEGER) {
            int shift = (int) arg2.value;
            if (shift >= 0 && shift < 32) {
                long unsignedValue = ((int) runtimeScalar.value) & 0xFFFFFFFFL;
                long result = (unsignedValue << shift) & 0xFFFFFFFFL;
                return new RuntimeScalar(result);
            }
        }

        // Check for uninitialized values and generate warnings
        // Use getDefinedBoolean() to handle tied scalars correctly
        if (!runtimeScalar.getDefinedBoolean()) {
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in left bitshift (<<)"),
                    RuntimeScalarCache.scalarEmptyString, "uninitialized");
        }
        if (!arg2.getDefinedBoolean()) {
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in left bitshift (<<)"),
                    RuntimeScalarCache.scalarEmptyString, "uninitialized");
        }

        // Convert string type to number if necessary
        if (runtimeScalar.isString()) {
            runtimeScalar = NumberParser.parseNumber(runtimeScalar);
        }

        // Check for special values only if it's a DOUBLE
        if (runtimeScalar.type == RuntimeScalarType.DOUBLE) {
            double doubleValue = runtimeScalar.getDouble();
            if (Double.isInfinite(doubleValue)) {
                if (doubleValue > 0) {
                    // +Inf should convert to UV_MAX (32-bit unsigned maximum)
                    return new RuntimeScalar(4294967295L); // 2^32 - 1
                } else {
                    // -Inf should convert to 0 for unsigned interpretation
                    return new RuntimeScalar(0L);
                }
            }
            if (Double.isNaN(doubleValue)) {
                // NaN should convert to 0
                return new RuntimeScalar(0L);
            }
        }

        long value = runtimeScalar.getLong();
        long shift = arg2.getLong();

        // Handle negative shift (reverse direction: left shift becomes right shift)
        if (shift < 0) {
            shift = -shift;
            if (shift < 0) shift = Long.MAX_VALUE;
            return shiftRightInternal(value, shift, false);
        }

        // Perl uses 32-bit word size for shift operations
        // Shifts >= 32 return 0
        if (shift >= 32) {
            return RuntimeScalarCache.scalarZero;
        }

        // Treat value as unsigned 32-bit (UV semantics)
        // Mask to 32 bits first to handle negative numbers correctly
        long unsignedValue = value & 0xFFFFFFFFL;

        // Perform the shift
        long result = (unsignedValue << shift) & 0xFFFFFFFFL;

        return new RuntimeScalar(result);
    }

    /**
     * Performs a right shift operation on a RuntimeScalar object.
     * Perl shifts treat negative numbers as unsigned (UV) by default.
     * Negative shift amounts reverse the direction (right shift becomes left shift).
     *
     * @param runtimeScalar The operand to be shifted.
     * @param arg2          The number of positions to shift.
     * @return A new RuntimeScalar with the result of the right shift operation.
     */
    public static RuntimeScalar shiftRight(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        // Fast path: both INTEGER with non-negative shift < 32
        int t1 = runtimeScalar.type;
        int t2 = arg2.type;
        if (t1 == RuntimeScalarType.INTEGER && t2 == RuntimeScalarType.INTEGER) {
            int shift = (int) arg2.value;
            if (shift >= 0 && shift < 32) {
                long unsignedValue = ((int) runtimeScalar.value) & 0xFFFFFFFFL;
                long result = unsignedValue >>> shift;
                return new RuntimeScalar(result);
            }
        }

        // Check for uninitialized values and generate warnings
        // Use getDefinedBoolean() to handle tied scalars correctly
        if (!runtimeScalar.getDefinedBoolean()) {
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in right bitshift (>>)"),
                    RuntimeScalarCache.scalarEmptyString, "uninitialized");
        }
        if (!arg2.getDefinedBoolean()) {
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in right bitshift (>>)"),
                    RuntimeScalarCache.scalarEmptyString, "uninitialized");
        }

        // Convert string type to number if necessary
        if (runtimeScalar.isString()) {
            runtimeScalar = NumberParser.parseNumber(runtimeScalar);
        }

        // Check for special values only if it's a DOUBLE
        if (runtimeScalar.type == RuntimeScalarType.DOUBLE) {
            double doubleValue = runtimeScalar.getDouble();
            if (Double.isInfinite(doubleValue)) {
                if (doubleValue > 0) {
                    // +Inf should convert to UV_MAX (32-bit), then shift right
                    long uvMax = 4294967295L; // 2^32 - 1
                    long shift = arg2.getLong();
                    if (shift >= 32) {
                        return RuntimeScalarCache.scalarZero;
                    }
                    return new RuntimeScalar(uvMax >>> shift);
                } else {
                    // -Inf should convert to 0 for unsigned interpretation
                    return RuntimeScalarCache.scalarZero;
                }
            }
            if (Double.isNaN(doubleValue)) {
                // NaN should convert to 0
                return RuntimeScalarCache.scalarZero;
            }
        }

        long value = runtimeScalar.getLong();
        long shift = arg2.getLong();

        // Handle negative shift (reverse direction: right shift becomes left shift)
        if (shift < 0) {
            shift = -shift;
            if (shift < 0) shift = Long.MAX_VALUE;
            if (shift >= 32) {
                return RuntimeScalarCache.scalarZero;
            }
            long unsignedValue = value & 0xFFFFFFFFL;
            long result = (unsignedValue << shift) & 0xFFFFFFFFL;
            return new RuntimeScalar(result);
        }

        return shiftRightInternal(value, shift, false);
    }

    /**
     * Internal helper for right shift operations.
     *
     * @param value  The value to shift
     * @param shift  The shift amount (must be non-negative)
     * @param signed If true, use signed (arithmetic) shift; if false, use unsigned (logical) shift
     * @return A new RuntimeScalar with the shifted value
     */
    private static RuntimeScalar shiftRightInternal(long value, long shift, boolean signed) {
        // Perl uses 32-bit word size for shift operations
        // Unsigned shifts >= 32 return 0
        if (shift >= 32) {
            if (signed) {
                // For signed right shift, stick to -1 or 0
                return new RuntimeScalar(value < 0 ? -1 : 0);
            }
            return RuntimeScalarCache.scalarZero;
        }

        if (signed) {
            // Signed (arithmetic) shift - sign bit propagates
            // First convert to signed 32-bit, then shift, then mask
            int signedValue = (int) value;
            long result = signedValue >> shift;
            return new RuntimeScalar(result);
        } else {
            // Unsigned (logical) shift - zero fill
            // Treat as unsigned 32-bit value
            long unsignedValue = value & 0xFFFFFFFFL;
            long result = unsignedValue >>> shift;
            return new RuntimeScalar(result);
        }
    }

    /**
     * Performs a left shift operation with signed (integer) semantics.
     * This is used when "use integer" pragma is in effect.
     * <p>
     * IMPORTANT: Must use 32-bit int arithmetic and >= 32 boundaries (not 64-bit long / >= 64).
     * PerlOnJava reports ivsize=4 in Config.pm, so bop.t expects 32-bit word-size behavior:
     * "use integer; 1 << 32" must return 0, and "1 << 31" must return -2147483648 (signed).
     * The "shift < 0" guard after negation catches Long.MIN_VALUE overflow (-Long.MIN_VALUE == Long.MIN_VALUE).
     *
     * @param runtimeScalar The operand to be shifted.
     * @param arg2          The number of positions to shift.
     * @return A new RuntimeScalar with the result of the integer left shift operation.
     */
    public static RuntimeScalar integerShiftLeft(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        if (!runtimeScalar.getDefinedBoolean()) {
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in left bitshift (<<)"),
                    RuntimeScalarCache.scalarEmptyString, "uninitialized");
        }
        if (!arg2.getDefinedBoolean()) {
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in left bitshift (<<)"),
                    RuntimeScalarCache.scalarEmptyString, "uninitialized");
        }

        if (runtimeScalar.isString()) {
            runtimeScalar = NumberParser.parseNumber(runtimeScalar);
        }

        // Use (int) getLong() — see integerBitwiseNot comment for why not getInt().
        int value = (int) runtimeScalar.getLong();
        long shift = arg2.getLong();

        if (shift < 0) {
            shift = -shift;
            if (shift < 0 || shift >= 32) {
                return new RuntimeScalar(value < 0 ? -1 : 0);
            }
            int result = value >> (int) shift;
            return new RuntimeScalar(result);
        }

        if (shift >= 32) {
            return RuntimeScalarCache.scalarZero;
        }

        int result = value << (int) shift;
        return new RuntimeScalar(result);
    }

    /**
     * Performs a right shift operation with signed (integer) semantics.
     * This is used when "use integer" pragma is in effect.
     * See integerShiftLeft javadoc for ivsize=4 / 32-bit constraints.
     *
     * @param runtimeScalar The operand to be shifted.
     * @param arg2          The number of positions to shift.
     * @return A new RuntimeScalar with the result of the integer right shift operation.
     */
    public static RuntimeScalar integerShiftRight(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        if (!runtimeScalar.getDefinedBoolean()) {
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in right bitshift (>>)"),
                    RuntimeScalarCache.scalarEmptyString, "uninitialized");
        }
        if (!arg2.getDefinedBoolean()) {
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in right bitshift (>>)"),
                    RuntimeScalarCache.scalarEmptyString, "uninitialized");
        }

        if (runtimeScalar.isString()) {
            runtimeScalar = NumberParser.parseNumber(runtimeScalar);
        }

        // Use (int) getLong() — see integerBitwiseNot comment for why not getInt().
        int value = (int) runtimeScalar.getLong();
        long shift = arg2.getLong();

        if (shift < 0) {
            shift = -shift;
            if (shift < 0 || shift >= 32) {
                return RuntimeScalarCache.scalarZero;
            }
            int result = value << (int) shift;
            return new RuntimeScalar(result);
        }

        if (shift >= 32) {
            return new RuntimeScalar(value < 0 ? -1 : 0);
        }

        int result = value >> (int) shift;
        return new RuntimeScalar(result);
    }
}