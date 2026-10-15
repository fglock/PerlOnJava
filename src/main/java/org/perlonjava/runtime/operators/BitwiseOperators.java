package org.perlonjava.runtime.operators;


import org.perlonjava.frontend.parser.NumberParser;
import org.perlonjava.runtime.runtimetypes.*;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.blessedId;

/**
 * This class provides methods for performing bitwise operations on RuntimeScalar objects.
 * It supports operations for both numeric and string types, with specific behavior for each.
 * Additionally, it implements Perl-like bitwise string operators.
 */
public class BitwiseOperators {
    private static final BigInteger UV_MASK = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final int UNSIGNED_WORD_CACHE_MASK = 4095;
    private static final AtomicReferenceArray<UnsignedWordResult> UNSIGNED_WORD_CACHE =
            new AtomicReferenceArray<>(UNSIGNED_WORD_CACHE_MASK + 1);
    private static final ThreadLocal<Boolean> COMPOUND_BITWISE_ASSIGNMENT =
            ThreadLocal.withInitial(() -> false);

    private record UnsignedWordResult(long bits, RuntimeScalarReadOnly scalar) {}

    private static void warnUninitializedBitwiseOperands(
            RuntimeScalar left, RuntimeScalar right, String operation, char symbol) {
        if (!COMPOUND_BITWISE_ASSIGNMENT.get() && !left.getDefinedBoolean()) {
            WarnDie.warnWithCategory(new RuntimeScalar(
                            "Use of uninitialized value in bitwise " + operation + " (" + symbol + ")"),
                    RuntimeScalarCache.scalarEmptyString, "uninitialized");
        }
        if (!right.getDefinedBoolean()) {
            WarnDie.warnWithCategory(new RuntimeScalar(
                            "Use of uninitialized value in bitwise " + operation + " (" + symbol + ")"),
                    RuntimeScalarCache.scalarEmptyString, "uninitialized");
        }
    }

    private static BigInteger unsignedValue(RuntimeScalar scalar) {
        return scalar.getBigint().and(UV_MASK);
    }

    private static RuntimeScalar unsignedResult(BigInteger value) {
        return new RuntimeScalar(value.and(UV_MASK));
    }

    private static boolean isInteger(RuntimeScalar scalar) {
        return scalar.type == RuntimeScalarType.INTEGER;
    }

    private static RuntimeScalar unsignedResult(long value) {
        if (value >= 0) return RuntimeScalarCache.getScalarInt(value);
        // Life and other bit-packed workloads repeatedly produce the same unsigned
        // word results. Cache the immutable scalar as well as its exact value, so
        // they avoid allocating a RuntimeScalar on every negative native-word result.
        return unsignedWordResult(value);
    }

    private static RuntimeScalarReadOnly unsignedWordResult(long bits) {
        int index = (int) (bits ^ (bits >>> 33) ^ (bits >>> 17))
                & UNSIGNED_WORD_CACHE_MASK;
        UnsignedWordResult cached = UNSIGNED_WORD_CACHE.get(index);
        if (cached != null && cached.bits == bits) return cached.scalar;

        BigInteger magnitude = BigInteger.valueOf(bits & Long.MAX_VALUE).setBit(63);
        RuntimeScalarReadOnly scalar = new RuntimeScalarReadOnly(magnitude);
        UNSIGNED_WORD_CACHE.set(index, new UnsignedWordResult(bits, scalar));
        return scalar;
    }

    private static long integerValue(RuntimeScalar scalar) {
        return ((Number) scalar.value).longValue();
    }

    private static RuntimeScalar integerBitwiseResult(
            RuntimeScalar left, RuntimeScalar right, long value) {
        return unsignedResult(value).propagateTaint(left, right);
    }

    /** Store an integer AND result directly into a plain scalar when it is nonnegative. */
    public static RuntimeScalar bitwiseAndInto(
            RuntimeScalar target, RuntimeScalar left, RuntimeScalar right) {
        if (!canBitwiseInto(target, left, right)) {
            return target.set(bitwiseAnd(left, right));
        }
        return bitwiseAndIntegerInto(target, left, right);
    }

    private static RuntimeScalar bitwiseAndIntegerInto(
            RuntimeScalar target, RuntimeScalar left, RuntimeScalar right) {
        return storeBitwiseResult(target, left, right,
                integerValue(left) & integerValue(right));
    }

    /** Store an integer OR result directly into a plain scalar when it is nonnegative. */
    public static RuntimeScalar bitwiseOrInto(
            RuntimeScalar target, RuntimeScalar left, RuntimeScalar right) {
        if (!canBitwiseInto(target, left, right)) {
            return target.set(bitwiseOr(left, right));
        }
        return bitwiseOrIntegerInto(target, left, right);
    }

    private static RuntimeScalar bitwiseOrIntegerInto(
            RuntimeScalar target, RuntimeScalar left, RuntimeScalar right) {
        return storeBitwiseResult(target, left, right,
                integerValue(left) | integerValue(right));
    }

    /** Store an integer XOR result directly into a plain scalar when it is nonnegative. */
    public static RuntimeScalar bitwiseXorInto(
            RuntimeScalar target, RuntimeScalar left, RuntimeScalar right) {
        if (!canBitwiseInto(target, left, right)) {
            return target.set(bitwiseXor(left, right));
        }
        return bitwiseXorIntegerInto(target, left, right);
    }

    private static RuntimeScalar bitwiseXorIntegerInto(
            RuntimeScalar target, RuntimeScalar left, RuntimeScalar right) {
        return storeBitwiseResult(target, left, right,
                integerValue(left) ^ integerValue(right));
    }

    private static boolean canBitwiseInto(
            RuntimeScalar target, RuntimeScalar left, RuntimeScalar right) {
        return target.canUpdateArithmeticIntegerInPlace()
                && left.type == RuntimeScalarType.INTEGER
                && right.type == RuntimeScalarType.INTEGER;
    }

    private static RuntimeScalar storeBitwiseResult(
            RuntimeScalar target, RuntimeScalar left, RuntimeScalar right, long result) {
        if (result >= 0) {
            boolean tainted = GlobalContext.isTaintModeActive()
                    && (left.isTainted() || right.isTainted());
            return target.setArithmeticIntegerResult(result, tainted);
        }
        // Negative native words must be represented as unsigned Perl values.
        return target.set(integerBitwiseResult(left, right, result));
    }

    private static RuntimeScalar unsignedShiftLeft(BigInteger value, long shift) {
        if (shift >= 64) return RuntimeScalarCache.scalarZero;
        return unsignedResult(value.shiftLeft((int) shift));
    }

    private static RuntimeScalar unsignedShiftRight(BigInteger value, long shift) {
        if (shift >= 64) return RuntimeScalarCache.scalarZero;
        return unsignedResult(value.shiftRight((int) shift));
    }

    private static RuntimeScalar unsignedShiftLeft(long value, long shift) {
        if (shift >= 64) return RuntimeScalarCache.scalarZero;
        return unsignedResult(value << (int) shift);
    }

    private static RuntimeScalar unsignedShiftRight(long value, long shift) {
        if (shift >= 64) return RuntimeScalarCache.scalarZero;
        return unsignedResult(value >>> (int) shift);
    }

    private static BigInteger exactInteger(RuntimeScalar scalar) {
        return scalar.type == RuntimeScalarType.INTEGER && scalar.value instanceof BigInteger
                ? (BigInteger) scalar.value : null;
    }

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
        int t1 = runtimeScalar.type;
        int t2 = arg2.type;
        if (isNumericBitwiseOperand(t1) && isNumericBitwiseOperand(t2)) {
            if (isInteger(runtimeScalar) && isInteger(arg2)) {
                return integerBitwiseResult(runtimeScalar, arg2,
                        integerValue(runtimeScalar) & integerValue(arg2));
            }
            return unsignedResult(unsignedValue(runtimeScalar).and(unsignedValue(arg2)))
                    .propagateTaint(runtimeScalar, arg2);
        }
        return bitwiseAndSlow(runtimeScalar, arg2, t1, t2);
    }

    private static RuntimeScalar bitwiseAndSlow(RuntimeScalar runtimeScalar, RuntimeScalar arg2,
                                                int t1, int t2) {
        // Check for overloaded '&' operator on blessed objects
        int blessId = blessedId(runtimeScalar);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(&", "&");
            if (result != null) return result.propagateTaint(runtimeScalar, arg2);
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

        // In Perl, bitwise ops dispatch based on internal type flags (SvNIOKp):
        // - If either operand has a numeric type (IOK/NOK), use numeric bitwise
        // - If both are non-numeric (strings from pack/vec, etc.), use string bitwise
        int vt1 = val1.type;
        int vt2 = val2.type;
        if (vt1 == RuntimeScalarType.INTEGER || vt1 == RuntimeScalarType.DOUBLE || vt1 == RuntimeScalarType.DUALVAR ||
                vt2 == RuntimeScalarType.INTEGER || vt2 == RuntimeScalarType.DOUBLE || vt2 == RuntimeScalarType.DUALVAR) {
            return bitwiseAndBinary(val1, val2);
        }
        return bitwiseAndDot(val1, val2);
    }

    /**
     * Performs a bitwise AND operation on two numeric RuntimeScalar objects.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise AND operation.
     */
    public static RuntimeScalar bitwiseAndBinary(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        if (isInteger(runtimeScalar) && isInteger(arg2)) {
            return integerBitwiseResult(runtimeScalar, arg2,
                    integerValue(runtimeScalar) & integerValue(arg2));
        }
        return unsignedResult(unsignedValue(runtimeScalar).and(unsignedValue(arg2)))
                .propagateTaint(runtimeScalar, arg2);
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
        int t1 = runtimeScalar.type;
        int t2 = arg2.type;
        if (t1 == RuntimeScalarType.INTEGER && t2 == RuntimeScalarType.INTEGER) {
            if (isInteger(runtimeScalar) && isInteger(arg2)) {
                return integerBitwiseResult(runtimeScalar, arg2,
                        integerValue(runtimeScalar) | integerValue(arg2));
            }
            return unsignedResult(unsignedValue(runtimeScalar).or(unsignedValue(arg2)))
                    .propagateTaint(runtimeScalar, arg2);
        }
        return bitwiseOrSlow(runtimeScalar, arg2, t1, t2);
    }

    private static RuntimeScalar bitwiseOrSlow(RuntimeScalar runtimeScalar, RuntimeScalar arg2,
                                               int t1, int t2) {
        // A real typeglob cannot be coerced into the numeric lvalue used by
        // `*FH |= 0`. Proxy/PVLV values remain eligible for string bitwise.
        if (runtimeScalar instanceof RuntimeGlob
                && (arg2.type == RuntimeScalarType.INTEGER
                    || arg2.type == RuntimeScalarType.DOUBLE
                    || arg2.type == RuntimeScalarType.DUALVAR)) {
            throw new PerlCompilerException("Can't coerce GLOB to integer in bitwise operation");
        }

        // Check for overloaded '|' operator on blessed objects
        int blessId = blessedId(runtimeScalar);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(|", "|");
            if (result != null) return result.propagateTaint(runtimeScalar, arg2);
        }

        // Fetch tied/readonly scalars once to avoid redundant FETCH calls
        RuntimeScalar val1 = t1 < RuntimeScalarType.TIED_SCALAR ? runtimeScalar :
                t1 == RuntimeScalarType.TIED_SCALAR ? runtimeScalar.tiedFetch() :
                        t1 == RuntimeScalarType.READONLY_SCALAR ? (RuntimeScalar) runtimeScalar.value : runtimeScalar;
        RuntimeScalar val2 = t2 < RuntimeScalarType.TIED_SCALAR ? arg2 :
                t2 == RuntimeScalarType.TIED_SCALAR ? arg2.tiedFetch() :
                        t2 == RuntimeScalarType.READONLY_SCALAR ? (RuntimeScalar) arg2.value : arg2;

        warnUninitializedBitwiseOperands(val1, val2, "or", '|');

        // In Perl, bitwise ops dispatch based on internal type flags (SvNIOKp):
        // - If either operand has a numeric type (IOK/NOK), use numeric bitwise
        // - If both are non-numeric (strings from pack/vec, etc.), use string bitwise
        int vt1 = val1.type;
        int vt2 = val2.type;
        if (vt1 == RuntimeScalarType.INTEGER || vt1 == RuntimeScalarType.DOUBLE || vt1 == RuntimeScalarType.DUALVAR ||
                vt2 == RuntimeScalarType.INTEGER || vt2 == RuntimeScalarType.DOUBLE || vt2 == RuntimeScalarType.DUALVAR) {
            return bitwiseOrBinary(val1, val2);
        }
        return bitwiseOrDot(val1, val2);
    }

    /**
     * Performs a bitwise OR operation on two numeric RuntimeScalar objects.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise OR operation.
     */
    public static RuntimeScalar bitwiseOrBinary(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        if (isInteger(runtimeScalar) && isInteger(arg2)) {
            return integerBitwiseResult(runtimeScalar, arg2,
                    integerValue(runtimeScalar) | integerValue(arg2));
        }
        return unsignedResult(unsignedValue(runtimeScalar).or(unsignedValue(arg2)))
                .propagateTaint(runtimeScalar, arg2);
    }

    /** Compound {@code |=} warns for an undefined right operand, but not an
     * undefined left operand. */
    public static RuntimeScalar bitwiseOrAssign(RuntimeScalar left, RuntimeScalar right) {
        boolean previous = COMPOUND_BITWISE_ASSIGNMENT.get();
        COMPOUND_BITWISE_ASSIGNMENT.set(true);
        try {
            return bitwiseOr(left, right);
        } finally {
            COMPOUND_BITWISE_ASSIGNMENT.set(previous);
        }
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
        int t1 = runtimeScalar.type;
        int t2 = arg2.type;
        if (t1 == RuntimeScalarType.INTEGER && t2 == RuntimeScalarType.INTEGER) {
            if (isInteger(runtimeScalar) && isInteger(arg2)) {
                return integerBitwiseResult(runtimeScalar, arg2,
                        integerValue(runtimeScalar) ^ integerValue(arg2));
            }
            return unsignedResult(unsignedValue(runtimeScalar).xor(unsignedValue(arg2)))
                    .propagateTaint(runtimeScalar, arg2);
        }
        return bitwiseXorSlow(runtimeScalar, arg2, t1, t2);
    }

    private static RuntimeScalar bitwiseXorSlow(RuntimeScalar runtimeScalar, RuntimeScalar arg2,
                                                int t1, int t2) {
        // Check for overloaded '^' operator on blessed objects
        int blessId = blessedId(runtimeScalar);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(^", "^");
            if (result != null) return result.propagateTaint(runtimeScalar, arg2);
        }

        // Fetch tied/readonly scalars once to avoid redundant FETCH calls
        RuntimeScalar val1 = t1 < RuntimeScalarType.TIED_SCALAR ? runtimeScalar :
                t1 == RuntimeScalarType.TIED_SCALAR ? runtimeScalar.tiedFetch() :
                        t1 == RuntimeScalarType.READONLY_SCALAR ? (RuntimeScalar) runtimeScalar.value : runtimeScalar;
        RuntimeScalar val2 = t2 < RuntimeScalarType.TIED_SCALAR ? arg2 :
                t2 == RuntimeScalarType.TIED_SCALAR ? arg2.tiedFetch() :
                        t2 == RuntimeScalarType.READONLY_SCALAR ? (RuntimeScalar) arg2.value : arg2;

        warnUninitializedBitwiseOperands(val1, val2, "xor", '^');

        // In Perl, bitwise ops dispatch based on internal type flags (SvNIOKp):
        // - If either operand has a numeric type (IOK/NOK), use numeric bitwise
        // - If both are non-numeric (strings from pack/vec, etc.), use string bitwise
        int vt1 = val1.type;
        int vt2 = val2.type;
        if (isNumericBitwiseOperand(vt1) || isNumericBitwiseOperand(vt2)) {
            return bitwiseXorBinary(val1, val2);
        }
        return bitwiseXorDot(val1, val2);
    }

    private static boolean isNumericBitwiseOperand(int type) {
        return type == RuntimeScalarType.INTEGER
                || type == RuntimeScalarType.DOUBLE
                || type == RuntimeScalarType.BOOLEAN
                || type == RuntimeScalarType.DUALVAR;
    }

    /**
     * Performs a bitwise XOR operation on two numeric RuntimeScalar objects.
     *
     * @param runtimeScalar The first operand.
     * @param arg2          The second operand.
     * @return A new RuntimeScalar with the result of the bitwise XOR operation.
     */
    public static RuntimeScalar bitwiseXorBinary(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        if (isInteger(runtimeScalar) && isInteger(arg2)) {
            return integerBitwiseResult(runtimeScalar, arg2,
                    integerValue(runtimeScalar) ^ integerValue(arg2));
        }
        return unsignedResult(unsignedValue(runtimeScalar).xor(unsignedValue(arg2)))
                .propagateTaint(runtimeScalar, arg2);
    }

    /** Compound {@code ^=} warns for an undefined right operand, but not an
     * undefined left operand. */
    public static RuntimeScalar bitwiseXorAssign(RuntimeScalar left, RuntimeScalar right) {
        boolean previous = COMPOUND_BITWISE_ASSIGNMENT.get();
        COMPOUND_BITWISE_ASSIGNMENT.set(true);
        try {
            return bitwiseXor(left, right);
        } finally {
            COMPOUND_BITWISE_ASSIGNMENT.set(previous);
        }
    }

    /** Numeric bitwise operations under {@code use integer}: return a signed IV. */
    public static RuntimeScalar integerBitwiseAnd(RuntimeScalar arg1, RuntimeScalar arg2) {
        return integerBitwiseBinary(arg1, arg2, '&');
    }

    public static RuntimeScalar integerBitwiseOr(RuntimeScalar arg1, RuntimeScalar arg2) {
        return integerBitwiseBinary(arg1, arg2, '|');
    }

    public static RuntimeScalar integerBitwiseXor(RuntimeScalar arg1, RuntimeScalar arg2) {
        return integerBitwiseBinary(arg1, arg2, '^');
    }

    private static RuntimeScalar integerBitwiseBinary(RuntimeScalar arg1, RuntimeScalar arg2, char operator) {
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            String symbol = Character.toString(operator);
            RuntimeScalar overloaded = OverloadContext.tryTwoArgumentOverload(
                    arg1, arg2, blessId, blessId2, "(" + symbol, symbol);
            if (overloaded != null) return overloaded.propagateTaint(arg1, arg2);
        }
        long a = nativeIntValue(arg1);
        long b = nativeIntValue(arg2);
        long result = switch (operator) {
            case '&' -> a & b;
            case '|' -> a | b;
            case '^' -> a ^ b;
            default -> throw new IllegalArgumentException("unknown bitwise operator: " + operator);
        };
        return new RuntimeScalar(result).propagateTaint(arg1, arg2);
    }

    private static long nativeIntValue(RuntimeScalar value) {
        RuntimeScalar number = value.getNumber("bitwise operation");
        if (number.type != RuntimeScalarType.DOUBLE) return number.getLong();
        return (long) number.getDouble();
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
            if (result != null) return result.propagateTaint(runtimeScalar);
        }

        // Fetch tied/readonly scalar once to avoid redundant FETCH calls
        RuntimeScalar val = runtimeScalar.type < RuntimeScalarType.TIED_SCALAR ? runtimeScalar :
                runtimeScalar.type == RuntimeScalarType.TIED_SCALAR ? runtimeScalar.tiedFetch() :
                        runtimeScalar.type == RuntimeScalarType.READONLY_SCALAR ? (RuntimeScalar) runtimeScalar.value : runtimeScalar;

        // In Perl, ~$val dispatches based on internal type flags (SvNIOKp):
        // - If the operand has a numeric type (IOK/NOK), use numeric NOT
        // - If it's a string, use string NOT (character-by-character)
        int vt = val.type;
        if (vt == RuntimeScalarType.INTEGER || vt == RuntimeScalarType.DOUBLE) {
            return bitwiseNotBinary(val).propagateTaint(runtimeScalar);
        }
        return bitwiseNotDot(val).propagateTaint(runtimeScalar);
    }

    /**
     * Performs a bitwise NOT operation on a numeric RuntimeScalar object.
     * This method now properly handles 32-bit unsigned integer semantics like Perl.
     *
     * @param runtimeScalar The operand.
     * @return A new RuntimeScalar with the result of the bitwise NOT operation.
     */
    public static RuntimeScalar bitwiseNotBinary(RuntimeScalar runtimeScalar) {
        if (isInteger(runtimeScalar)) {
            return unsignedResult(~((Number) runtimeScalar.value).longValue())
                    .propagateTaint(runtimeScalar);
        }
        return unsignedResult(unsignedValue(runtimeScalar).xor(UV_MASK))
                .propagateTaint(runtimeScalar);
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
            if (result != null) return result.propagateTaint(runtimeScalar);
        }

        // Fetch tied/readonly scalar once to avoid redundant FETCH calls
        RuntimeScalar val = runtimeScalar.type < RuntimeScalarType.TIED_SCALAR ? runtimeScalar :
                runtimeScalar.type == RuntimeScalarType.TIED_SCALAR ? runtimeScalar.tiedFetch() :
                        runtimeScalar.type == RuntimeScalarType.READONLY_SCALAR ? (RuntimeScalar) runtimeScalar.value : runtimeScalar;

        // In Perl, ~$val dispatches based on internal type flags (SvNIOKp):
        // - If the operand has a numeric type (IOK/NOK), use numeric NOT
        // - If it's a string, use string NOT (character-by-character)
        int vt = val.type;
        if (vt != RuntimeScalarType.INTEGER && vt != RuntimeScalarType.DOUBLE) {
            return bitwiseNotDot(val).propagateTaint(runtimeScalar);
        }

        long value = val.getLong();
        long result = ~value;
        return new RuntimeScalar(result).propagateTaint(runtimeScalar);
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
        // Perl rejects a wide character anywhere in either operand, even
        // beyond the shorter string's end (where & itself would truncate).
        // Checking only the overlap incorrectly lets `"abc" & "abc\\x{100}"`
        // succeed.
        validateByteStringOperand(s1, "and", '&');
        validateByteStringOperand(s2, "and", '&');
        int len = Math.min(s1.length(), s2.length());
        StringBuilder result = new StringBuilder(len);

        for (int i = 0; i < len; i++) {
            char c1 = s1.charAt(i);
            char c2 = s2.charAt(i);
            result.append((char) (c1 & c2));
        }

        return stringBitwiseResult(result.toString(),
                runtimeScalar.type == RuntimeScalarType.STRING
                        || arg2.type == RuntimeScalarType.STRING)
                .propagateTaint(runtimeScalar, arg2);
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

        return stringBitwiseResult(result.toString(),
                runtimeScalar.type == RuntimeScalarType.STRING
                        || arg2.type == RuntimeScalarType.STRING)
                .propagateTaint(runtimeScalar, arg2);
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

        return stringBitwiseResult(result.toString(),
                runtimeScalar.type == RuntimeScalarType.STRING
                        || arg2.type == RuntimeScalarType.STRING)
                .propagateTaint(runtimeScalar, arg2);
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
                throw new PerlCompilerException("Use of strings with code points over 0xFF as arguments to 1's complement (~) operator is not allowed");
            }
            result.append((char) ((~c) & 0xFF));
        }

        // Perl's string complement returns an octet string even when the
        // operand carries the UTF-8 flag (for code points representable as
        // bytes, which is the range accepted above).
        return stringBitwiseResult(result.toString(), false).propagateTaint(runtimeScalar);
    }

    private static RuntimeScalar stringBitwiseResult(String value, boolean utf8) {
        RuntimeScalar result = new RuntimeScalar(value);
        if (!utf8) {
            result.type = RuntimeScalarType.BYTE_STRING;
        }
        return result;
    }

    private static void validateByteStringOperand(String value, String operation, char symbol) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0xFF) {
                throw new PerlCompilerException(
                        "Use of strings with code points over 0xFF as arguments to bitwise "
                                + operation + " (" + symbol + ") operator is not allowed");
            }
        }
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
        runtimeScalar = RuntimeScalar.fetchTiedOnce(runtimeScalar);
        arg2 = RuntimeScalar.fetchTiedOnce(arg2);
        // Fast path: both INTEGER with non-negative shift within Java's 64-bit word.
        int t1 = runtimeScalar.type;
        int t2 = arg2.type;
        if (t1 == RuntimeScalarType.INTEGER && t2 == RuntimeScalarType.INTEGER
                && exactInteger(arg2) == null) {
            long shift = arg2.getLong();
            if (shift >= 0) {
                return unsignedShiftLeft(((Number) runtimeScalar.value).longValue(), shift);
            } else if (shift != Long.MIN_VALUE) {
                return unsignedShiftRight(((Number) runtimeScalar.value).longValue(), -shift);
            }
            return RuntimeScalarCache.scalarZero;
        }

        // Check for overloaded '<<' operator on blessed objects
        int blessIdL = blessedId(runtimeScalar);
        int blessIdL2 = blessedId(arg2);
        if (blessIdL < 0 || blessIdL2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessIdL, blessIdL2, "(<<", "<<");
            if (result != null) return result;
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
                    return unsignedResult(UV_MASK);
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

        BigInteger exactShift = exactInteger(arg2);
        if (runtimeScalar.type == RuntimeScalarType.INTEGER) {
            long value = ((Number) runtimeScalar.value).longValue();
            if (exactShift != null) {
                if (exactShift.signum() >= 0) {
                    return exactShift.compareTo(BigInteger.valueOf(64)) >= 0
                            ? RuntimeScalarCache.scalarZero
                            : unsignedShiftLeft(value, exactShift.longValue());
                }
                BigInteger magnitude = exactShift.negate();
                return magnitude.compareTo(BigInteger.valueOf(64)) >= 0
                        ? RuntimeScalarCache.scalarZero
                        : unsignedShiftRight(value, magnitude.longValue());
            }
            long shift = arg2.getLong();
            if (shift < 0) {
                if (shift == Long.MIN_VALUE) return RuntimeScalarCache.scalarZero;
                return unsignedShiftRight(value, -shift);
            }
            return unsignedShiftLeft(value, shift);
        }
        BigInteger value = unsignedValue(runtimeScalar);
        if (exactShift != null) {
            if (exactShift.signum() >= 0) {
                return exactShift.compareTo(BigInteger.valueOf(64)) >= 0
                        ? RuntimeScalarCache.scalarZero
                        : unsignedShiftLeft(value, exactShift.longValue());
            }
            BigInteger magnitude = exactShift.negate();
            return magnitude.compareTo(BigInteger.valueOf(64)) >= 0
                    ? RuntimeScalarCache.scalarZero
                    : unsignedShiftRight(value, magnitude.longValue());
        }
        long shift = arg2.getLong();

        // Handle negative shift (reverse direction: left shift becomes right shift)
        if (shift < 0) {
            shift = -shift;
            if (shift < 0) shift = Long.MAX_VALUE;
            return unsignedShiftRight(value, shift);
        }
        return unsignedShiftLeft(value, shift);
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
        runtimeScalar = RuntimeScalar.fetchTiedOnce(runtimeScalar);
        arg2 = RuntimeScalar.fetchTiedOnce(arg2);
        // Fast path: both INTEGER with non-negative shift within Java's 64-bit word.
        int t1 = runtimeScalar.type;
        int t2 = arg2.type;
        if (t1 == RuntimeScalarType.INTEGER && t2 == RuntimeScalarType.INTEGER
                && exactInteger(arg2) == null) {
            long shift = arg2.getLong();
            if (shift >= 0) {
                return unsignedShiftRight(((Number) runtimeScalar.value).longValue(), shift);
            } else if (shift != Long.MIN_VALUE) {
                return unsignedShiftLeft(((Number) runtimeScalar.value).longValue(), -shift);
            }
            return RuntimeScalarCache.scalarZero;
        }

        // Check for overloaded '>>' operator on blessed objects
        int blessIdR = blessedId(runtimeScalar);
        int blessIdR2 = blessedId(arg2);
        if (blessIdR < 0 || blessIdR2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessIdR, blessIdR2, "(>>", ">>");
            if (result != null) return result;
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
                    long shift = arg2.getLong();
                    return unsignedShiftRight(UV_MASK, shift);
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

        BigInteger exactShift = exactInteger(arg2);
        if (runtimeScalar.type == RuntimeScalarType.INTEGER) {
            long value = ((Number) runtimeScalar.value).longValue();
            if (exactShift != null) {
                if (exactShift.signum() >= 0) {
                    return exactShift.compareTo(BigInteger.valueOf(64)) >= 0
                            ? RuntimeScalarCache.scalarZero
                            : unsignedShiftRight(value, exactShift.longValue());
                }
                BigInteger magnitude = exactShift.negate();
                return magnitude.compareTo(BigInteger.valueOf(64)) >= 0
                        ? RuntimeScalarCache.scalarZero
                        : unsignedShiftLeft(value, magnitude.longValue());
            }
            long shift = arg2.getLong();
            if (shift < 0) {
                if (shift == Long.MIN_VALUE) return RuntimeScalarCache.scalarZero;
                return unsignedShiftLeft(value, -shift);
            }
            return unsignedShiftRight(value, shift);
        }
        BigInteger value = unsignedValue(runtimeScalar);
        if (exactShift != null) {
            if (exactShift.signum() >= 0) {
                return exactShift.compareTo(BigInteger.valueOf(64)) >= 0
                        ? RuntimeScalarCache.scalarZero
                        : unsignedShiftRight(value, exactShift.longValue());
            }
            BigInteger magnitude = exactShift.negate();
            return magnitude.compareTo(BigInteger.valueOf(64)) >= 0
                    ? RuntimeScalarCache.scalarZero
                    : unsignedShiftLeft(value, magnitude.longValue());
        }
        long shift = arg2.getLong();

        // Handle negative shift (reverse direction: right shift becomes left shift)
        if (shift < 0) {
            shift = -shift;
            if (shift < 0) shift = Long.MAX_VALUE;
            return unsignedShiftLeft(value, shift);
        }
        return unsignedShiftRight(value, shift);
    }

    /**
     * Performs a left shift operation with signed (integer) semantics.
     * This is used when "use integer" pragma is in effect.
     * <p>
     * Uses Java's 64-bit signed word, matching a 64-bit Perl under `use integer`.
     * The "shift < 0" guard after negation catches Long.MIN_VALUE overflow (-Long.MIN_VALUE == Long.MIN_VALUE).
     *
     * @param runtimeScalar The operand to be shifted.
     * @param arg2          The number of positions to shift.
     * @return A new RuntimeScalar with the result of the integer left shift operation.
     */
    public static RuntimeScalar integerShiftLeft(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        runtimeScalar = RuntimeScalar.fetchTiedOnce(runtimeScalar);
        arg2 = RuntimeScalar.fetchTiedOnce(arg2);
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

        long value = runtimeScalar.getLong();
        long shift = arg2.getLong();

        if (shift < 0) {
            shift = -shift;
            if (shift < 0 || shift >= 64) {
                return new RuntimeScalar(value < 0 ? -1 : 0);
            }
            long result = value >> (int) shift;
            return new RuntimeScalar(result);
        }

        if (shift >= 64) {
            return RuntimeScalarCache.scalarZero;
        }

        long result = value << (int) shift;
        return new RuntimeScalar(result);
    }

    /**
     * Performs a right shift operation with signed (integer) semantics.
     * This is used when "use integer" pragma is in effect.
     * See integerShiftLeft javadoc for signed 64-bit behavior.
     *
     * @param runtimeScalar The operand to be shifted.
     * @param arg2          The number of positions to shift.
     * @return A new RuntimeScalar with the result of the integer right shift operation.
     */
    public static RuntimeScalar integerShiftRight(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        runtimeScalar = RuntimeScalar.fetchTiedOnce(runtimeScalar);
        arg2 = RuntimeScalar.fetchTiedOnce(arg2);
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

        long value = runtimeScalar.getLong();
        long shift = arg2.getLong();

        if (shift < 0) {
            shift = -shift;
            if (shift < 0 || shift >= 64) {
                return RuntimeScalarCache.scalarZero;
            }
            long result = value << (int) shift;
            return new RuntimeScalar(result);
        }

        if (shift >= 64) {
            return new RuntimeScalar(value < 0 ? -1 : 0);
        }

        long result = value >> (int) shift;
        return new RuntimeScalar(result);
    }
}
