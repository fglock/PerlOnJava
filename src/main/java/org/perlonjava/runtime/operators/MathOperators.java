package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.runtimetypes.*;

import java.math.BigInteger;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

/**
 * Provides basic arithmetic operations for RuntimeScalar objects.
 * This class includes methods for addition, subtraction, multiplication,
 * division, modulus, and various mathematical functions.
 */
public class MathOperators {
    private static final BigInteger MIN_IV = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger MAX_UV = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private static RuntimeScalar integerResult(BigInteger result) {
        if (result.compareTo(MIN_IV) >= 0 && result.compareTo(MAX_UV) <= 0) {
            return new RuntimeScalar(result);
        }
        return new RuntimeScalar(result.doubleValue());
    }

    /**
     * Keep the string channel of a scalar which entered arithmetic as a
     * string. Perl's numeric operators add an IV/NV value to the existing PV
     * channel; dropping that channel makes deep comparisons distinguish an
     * arithmetic result from an otherwise identical string.
     */
    private static RuntimeScalar preserveStringChannel(RuntimeScalar result,
                                                        RuntimeScalar left,
                                                        RuntimeScalar right) {
        if (!isStringLike(left) && !isStringLike(right)) return result;
        // Small integer results commonly come from RuntimeScalarCache as a
        // READONLY_SCALAR proxy. Use a detached numeric scalar in the dualvar
        // so the result remains mutable and its numeric channel is retained.
        if (result.type == READONLY_SCALAR) result = new RuntimeScalar(result);
        if (result.type != INTEGER && result.type != DOUBLE) return result;

        RuntimeScalar dual = new RuntimeScalar();
        dual.type = DUALVAR;
        dual.value = new DualVar(result, new RuntimeScalar(result.toString()));
        dual.tainted = result.tainted;
        dual.numericLiteralText = result.numericLiteralText;
        return dual;
    }

    private static boolean isStringLike(RuntimeScalar scalar) {
        return scalar.type == STRING || scalar.type == BYTE_STRING || scalar.type == VSTRING;
    }

    private static RuntimeScalar numericOperand(RuntimeScalar scalar, String operation) {
        // Numeric conversion operates on an SV's numeric slot. Do not mark
        // the original array/hash/lvalue slot as numified while evaluating a
        // non-mutating binary operator.
        RuntimeScalar operand = scalar.type == READONLY_SCALAR
                || isStringLike(scalar) ? new RuntimeScalar(scalar) : scalar;
        return operand.getNumber(operation);
    }

    private static RuntimeScalar numericOperandWarn(RuntimeScalar scalar, String operation) {
        RuntimeScalar operand = scalar.type == READONLY_SCALAR
                || isStringLike(scalar) ? new RuntimeScalar(scalar) : scalar;
        return operand.getNumberWarn(operation);
    }

    private static boolean hasWideInteger(RuntimeScalar scalar) {
        return scalar.type == INTEGER && scalar.value instanceof BigInteger;
    }

    private static boolean hasWideInteger(RuntimeScalar left, RuntimeScalar right) {
        return hasWideInteger(left) || hasWideInteger(right);
    }

    /**
     * Return the exact integer represented by an IV or by an NV that Perl can
     * losslessly coerce back to a signed IV for multiplication.  Perl's
     * pp_multiply takes this path before falling back to NV arithmetic; in
     * particular, integral powers stored in an NV can still produce an exact
     * IV/UV product.
     */
    private static BigInteger exactMultiplyInteger(RuntimeScalar scalar) {
        if (scalar.type == INTEGER) return scalar.getBigint();
        if (scalar.type != DOUBLE) return null;

        double value = scalar.getDouble();
        // The upper bound is exclusive: Java's double representation of
        // Long.MAX_VALUE rounds to 2^63, which Perl cannot coerce to an IV.
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < -0x1.0p63 || value >= 0x1.0p63) {
            return null;
        }
        long integer = (long) value;
        return (double) integer == value ? BigInteger.valueOf(integer) : null;
    }

    private static RuntimeScalar exactIntegralProduct(RuntimeScalar left,
                                                       RuntimeScalar right) {
        BigInteger leftInteger = exactMultiplyInteger(left);
        if (leftInteger == null) return null;
        BigInteger rightInteger = exactMultiplyInteger(right);
        if (rightInteger == null) return null;
        return integerResult(leftInteger.multiply(rightInteger));
    }

    /** Largest magnitude such that every integer in [-N, N] is exactly representable as double (2^53). */
    private static final double MAX_EXACT_DOUBLE_INT = 9007199254740992.0;

    /** Operations whose native-IV result is truncated to PerlOnJava's 32-bit ivsize. */
    private enum IntegerOperation { ADD, SUBTRACT, MULTIPLY }

    private static RuntimeScalar integerBinary(RuntimeScalar arg1, RuntimeScalar arg2,
                                               IntegerOperation operation, boolean warn,
                                               boolean overload) {
        if (overload) {
            int blessId = blessedId(arg1);
            int blessId2 = blessedId(arg2);
            if (blessId < 0 || blessId2 < 0) {
                String symbol = switch (operation) {
                    case ADD -> "+";
                    case SUBTRACT -> "-";
                    case MULTIPLY -> "*";
                };
                RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(
                        arg1, arg2, blessId, blessId2, "(" + symbol, symbol);
                if (result != null) return result;
            }
        }

        String context = switch (operation) {
            case ADD -> "addition (+)";
            case SUBTRACT -> "subtraction (-)";
            case MULTIPLY -> "multiplication (*)";
        };
        arg1 = warn ? arg1.getNumberWarn(context) : arg1.getNumber(context);
        arg2 = warn ? arg2.getNumberWarn(context) : arg2.getNumber(context);
        long a = arg1.getLong();
        long b = arg2.getLong();
        long result = switch (operation) {
            case ADD -> a + b;
            case SUBTRACT -> a - b;
            case MULTIPLY -> a * b;
        };
        return new RuntimeScalar(result);
    }

    public static RuntimeScalar integerAdd(RuntimeScalar arg1, RuntimeScalar arg2) {
        return integerBinary(arg1, arg2, IntegerOperation.ADD, false, true);
    }

    public static RuntimeScalar integerAddWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        return integerBinary(arg1, arg2, IntegerOperation.ADD, true, true);
    }

    public static RuntimeScalar integerAddNoOverload(RuntimeScalar arg1, RuntimeScalar arg2) {
        return integerBinary(arg1, arg2, IntegerOperation.ADD, false, false);
    }

    public static RuntimeScalar integerSubtract(RuntimeScalar arg1, RuntimeScalar arg2) {
        return integerBinary(arg1, arg2, IntegerOperation.SUBTRACT, false, true);
    }

    public static RuntimeScalar integerSubtractWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        return integerBinary(arg1, arg2, IntegerOperation.SUBTRACT, true, true);
    }

    public static RuntimeScalar integerSubtractNoOverload(RuntimeScalar arg1, RuntimeScalar arg2) {
        return integerBinary(arg1, arg2, IntegerOperation.SUBTRACT, false, false);
    }

    public static RuntimeScalar integerMultiply(RuntimeScalar arg1, RuntimeScalar arg2) {
        return integerBinary(arg1, arg2, IntegerOperation.MULTIPLY, false, true);
    }

    public static RuntimeScalar integerMultiplyWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        return integerBinary(arg1, arg2, IntegerOperation.MULTIPLY, true, true);
    }

    public static RuntimeScalar integerMultiplyNoOverload(RuntimeScalar arg1, RuntimeScalar arg2) {
        return integerBinary(arg1, arg2, IntegerOperation.MULTIPLY, false, false);
    }

    public static RuntimeScalar integerUnaryMinus(RuntimeScalar arg) {
        int blessId = blessedId(arg);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(
                    arg, blessId, "(neg", "neg", MathOperators::integerUnaryMinus, "(-");
            if (result != null) return result;
        }
        RuntimeScalar stringResult = unaryMinusStringResult(arg);
        if (stringResult != null) return stringResult;
        return new RuntimeScalar(-nativeIntValue(arg, "negation (-)"));
    }

    public static RuntimeScalar integerUnaryMinusNoOverload(RuntimeScalar arg) {
        RuntimeScalar stringResult = unaryMinusStringResult(arg);
        if (stringResult != null) return stringResult;
        return new RuntimeScalar(-nativeIntValue(arg, "negation (-)"));
    }

    private static long nativeIntValue(RuntimeScalar arg, String operation) {
        RuntimeScalar number = arg.getNumber(operation);
        if (number.type != DOUBLE) return number.getLong();
        return (long) number.getDouble();
    }

    /** Perl's unary-minus string sign toggling, or {@code null} for numeric coercion. */
    private static RuntimeScalar unaryMinusStringResult(RuntimeScalar runtimeScalar) {
        if (!runtimeScalar.isString()) return null;
        String input = runtimeScalar.toString();
        if (input.length() < 2) {
            if (input.isEmpty()) return getScalarInt(0);
            if (input.equals("-")) return new RuntimeScalar("+");
            if (input.equals("+")) return new RuntimeScalar("-");
        }
        if (!input.matches("^\\s*[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?\\s*$")) {
            if (input.startsWith("-")) return new RuntimeScalar("+" + input.substring(1));
            if (input.startsWith("+")) return new RuntimeScalar("-" + input.substring(1));
            if (input.matches("^[_A-Za-z].*")) return new RuntimeScalar("-" + input);
        }
        return null;
    }

    public static RuntimeScalar integerAddAssign(RuntimeScalar arg1, RuntimeScalar arg2) {
        RuntimeScalar overloaded = compoundIntegerOverload(arg1, arg2, "(+=", "+=", "(+");
        arg1.set(overloaded != null ? overloaded : integerAdd(arg1, arg2));
        return arg1;
    }

    public static RuntimeScalar integerSubtractAssign(RuntimeScalar arg1, RuntimeScalar arg2) {
        RuntimeScalar overloaded = compoundIntegerOverload(arg1, arg2, "(-=", "-=", "(-");
        arg1.set(overloaded != null ? overloaded : integerSubtract(arg1, arg2));
        return arg1;
    }

    public static RuntimeScalar integerMultiplyAssignWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        RuntimeScalar overloaded = compoundIntegerOverload(arg1, arg2, "(*=", "*=", "(*");
        arg1.set(overloaded != null ? overloaded : integerMultiplyWarn(arg1, arg2));
        return arg1;
    }

    private static RuntimeScalar compoundIntegerOverload(RuntimeScalar arg1, RuntimeScalar arg2,
                                                         String copyKey, String assignKey, String baseKey) {
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        return blessId < 0 || blessId2 < 0
                ? OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2,
                        copyKey, assignKey, baseKey)
                : null;
    }

    /**
     * Adds an integer to a RuntimeScalar and returns the result.
     *
     * @param arg1 The RuntimeScalar to add to.
     * @param arg2 The integer value to add.
     * @return A new RuntimeScalar representing the sum.
     */
    public static RuntimeScalar add(RuntimeScalar arg1, int arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, new RuntimeScalar(arg2), blessId, 0, "(+", "+");
            if (result != null) return result;
        }

        // The compiler normally selects addWarn from lexical warning bits,
        // but Perl's dynamically localized $^W can enable warnings after that
        // selection (notably after BEGIN assigns caller()[9] to
        // ${^WARNING_BITS}).  Preserve that runtime override here too.
        arg1 = org.perlonjava.runtime.perlmodule.Warnings.isWarnFlagLocalized()
                && org.perlonjava.runtime.perlmodule.Warnings.isWarnFlagSet()
                ? arg1.getNumberWarn("addition (+)")
                : arg1.getNumber("addition (+)");
        // Perform addition based on the type of RuntimeScalar
        if (arg1.type == DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() + arg2);
        } else if (hasWideInteger(arg1)) {
            return integerResult(arg1.getBigint().add(BigInteger.valueOf(arg2)));
        } else {
            long a = arg1.getLong();
            try {
                // Note: do not cache, because the result of addition is mutable - t/comp/fold.t
                return new RuntimeScalar(Math.addExact(a, arg2));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return integerResult(BigInteger.valueOf(a).add(BigInteger.valueOf(arg2)));
            }
        }
    }

    /**
     * Adds an integer to a RuntimeScalar with uninitialized value warnings.
     * Called when 'use warnings "uninitialized"' is in effect.
     *
     * @param arg1 The RuntimeScalar to add to.
     * @param arg2 The integer value to add.
     * @return A new RuntimeScalar representing the sum.
     */
    public static RuntimeScalar addWarn(RuntimeScalar arg1, int arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, new RuntimeScalar(arg2), blessId, 0, "(+", "+");
            if (result != null) return result;
        }

        // Convert to number with warning for uninitialized values
        arg1 = arg1.getNumberWarn("addition (+)");

        // Perform addition based on the type of RuntimeScalar
        if (arg1.type == DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() + arg2);
        } else {
            long a = arg1.getLong();
            try {
                // Note: do not cache, because the result of addition is mutable - t/comp/fold.t
                return new RuntimeScalar(Math.addExact(a, arg2));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return integerResult(BigInteger.valueOf(a).add(BigInteger.valueOf(arg2)));
            }
        }
    }

    /**
     * Adds two RuntimeScalar objects and returns the result.
     *
     * @param arg1 The first RuntimeScalar to add.
     * @param arg2 The second RuntimeScalar to add.
     * @return A new RuntimeScalar representing the sum.
     */
    public static RuntimeScalar add(RuntimeScalar arg1, RuntimeScalar arg2) {
        return preserveStringChannel(addUnpropagated(arg1, arg2), arg1, arg2)
                .propagateTaint(arg1, arg2);
    }

    private static RuntimeScalar addUnpropagated(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Fast path: both INTEGER - skip blessedId check, getNumber(), type checks
        if (arg1.type == INTEGER && arg2.type == INTEGER) {
            if (hasWideInteger(arg1, arg2)) {
                return integerResult(arg1.getBigint().add(arg2.getBigint()));
            }
            long a = arg1.getLong();
            long b = arg2.getLong();
            try {
                return getScalarInt(Math.addExact(a, b));
            } catch (ArithmeticException ignored) {
                return integerResult(BigInteger.valueOf(a).add(BigInteger.valueOf(b)));
            }
        }

        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(+", "+");
            if (result != null) return result;
        }

        // A local $^W can turn warnings on dynamically even when lexical bits
        // made the compiler choose this ordinary (rather than addWarn) path.
        boolean dynamicWarnings = org.perlonjava.runtime.perlmodule.Warnings.isWarnFlagLocalized()
                && org.perlonjava.runtime.perlmodule.Warnings.isWarnFlagSet();
        arg1 = dynamicWarnings
                ? numericOperandWarn(arg1, "addition (+)")
                : numericOperand(arg1, "addition (+)");
        arg2 = dynamicWarnings
                ? numericOperandWarn(arg2, "addition (+)")
                : numericOperand(arg2, "addition (+)");
        // Perform addition based on the type of RuntimeScalar
        if (arg1.type == DOUBLE || arg2.type == DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() + arg2.getDouble());
        } else if (hasWideInteger(arg1, arg2)) {
            return integerResult(arg1.getBigint().add(arg2.getBigint()));
        } else {
            long a = arg1.getLong();
            long b = arg2.getLong();
            try {
                return getScalarInt(Math.addExact(a, b));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return integerResult(BigInteger.valueOf(a).add(BigInteger.valueOf(b)));
            }
        }
    }

    /**
     * Adds two RuntimeScalar objects with uninitialized value warnings.
     * Called when 'use warnings "uninitialized"' is in effect.
     *
     * @param arg1 The first RuntimeScalar to add.
     * @param arg2 The second RuntimeScalar to add.
     * @return A new RuntimeScalar representing the sum.
     */
    public static RuntimeScalar addWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        return preserveStringChannel(addWarnUnpropagated(arg1, arg2), arg1, arg2)
                .propagateTaint(arg1, arg2);
    }

    private static RuntimeScalar addWarnUnpropagated(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Fast path: both INTEGER - skip blessedId check, getNumber(), type checks
        if (arg1.type == INTEGER && arg2.type == INTEGER) {
            if (hasWideInteger(arg1, arg2)) {
                return integerResult(arg1.getBigint().add(arg2.getBigint()));
            }
            long a = arg1.getLong();
            long b = arg2.getLong();
            try {
                return getScalarInt(Math.addExact(a, b));
            } catch (ArithmeticException ignored) {
                return integerResult(BigInteger.valueOf(a).add(BigInteger.valueOf(b)));
            }
        }

        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(+", "+");
            if (result != null) return result;
        }

        // Convert to number with warning for uninitialized values
        arg1 = arg1.getNumberWarn("addition (+)");
        arg2 = arg2.getNumberWarn("addition (+)");

        // Perform addition based on the type of RuntimeScalar
        if (arg1.type == DOUBLE || arg2.type == DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() + arg2.getDouble());
        } else if (hasWideInteger(arg1, arg2)) {
            return integerResult(arg1.getBigint().add(arg2.getBigint()));
        } else {
            long a = arg1.getLong();
            long b = arg2.getLong();
            try {
                return getScalarInt(Math.addExact(a, b));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return integerResult(BigInteger.valueOf(a).add(BigInteger.valueOf(b)));
            }
        }
    }

    /**
     * Subtracts an integer from a RuntimeScalar and returns the result.
     *
     * @param arg1 The RuntimeScalar to subtract from.
     * @param arg2 The integer value to subtract.
     * @return A new RuntimeScalar representing the difference.
     */
    public static RuntimeScalar subtract(RuntimeScalar arg1, int arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, new RuntimeScalar(arg2), blessId, 0, "(-", "-");
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        arg1 = arg1.getNumber("subtraction (-)");
        // Perform subtraction based on the type of RuntimeScalar
        if (arg1.type == DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() - arg2);
        } else if (hasWideInteger(arg1)) {
            return integerResult(arg1.getBigint().subtract(BigInteger.valueOf(arg2)));
        } else {
            long a = arg1.getLong();
            try {
                return getScalarInt(Math.subtractExact(a, arg2));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return integerResult(BigInteger.valueOf(a).subtract(BigInteger.valueOf(arg2)));
            }
        }
    }

    /**
     * Subtracts an integer from a RuntimeScalar with uninitialized value warnings.
     * Called when 'use warnings "uninitialized"' is in effect.
     *
     * @param arg1 The RuntimeScalar to subtract from.
     * @param arg2 The integer value to subtract.
     * @return A new RuntimeScalar representing the difference.
     */
    public static RuntimeScalar subtractWarn(RuntimeScalar arg1, int arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, new RuntimeScalar(arg2), blessId, 0, "(-", "-");
            if (result != null) return result;
        }

        // Convert to number with warning for uninitialized values
        arg1 = arg1.getNumberWarn("subtraction (-)");

        // Perform subtraction based on the type of RuntimeScalar
        if (arg1.type == DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() - arg2);
        } else if (hasWideInteger(arg1)) {
            return integerResult(arg1.getBigint().subtract(BigInteger.valueOf(arg2)));
        } else {
            long a = arg1.getLong();
            try {
                return getScalarInt(Math.subtractExact(a, arg2));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return integerResult(BigInteger.valueOf(a).subtract(BigInteger.valueOf(arg2)));
            }
        }
    }

    /**
     * Subtracts one RuntimeScalar from another and returns the result.
     *
     * @param arg1 The RuntimeScalar to subtract from.
     * @param arg2 The RuntimeScalar to subtract.
     * @return A new RuntimeScalar representing the difference.
     */
    public static RuntimeScalar subtract(RuntimeScalar arg1, RuntimeScalar arg2) {
        return preserveStringChannel(subtractUnpropagated(arg1, arg2), arg1, arg2)
                .propagateTaint(arg1, arg2);
    }

    private static RuntimeScalar subtractUnpropagated(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Fast path: both INTEGER - skip blessedId check, getNumber(), type checks
        if (arg1.type == INTEGER && arg2.type == INTEGER) {
            if (hasWideInteger(arg1, arg2)) {
                return integerResult(arg1.getBigint().subtract(arg2.getBigint()));
            }
            long a = arg1.getLong();
            long b = arg2.getLong();
            try {
                return getScalarInt(Math.subtractExact(a, b));
            } catch (ArithmeticException ignored) {
                return integerResult(BigInteger.valueOf(a).subtract(BigInteger.valueOf(b)));
            }
        }

        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(-", "-");
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        arg1 = numericOperand(arg1, "subtraction (-)");
        arg2 = numericOperand(arg2, "subtraction (-)");
        // Perform subtraction based on the type of RuntimeScalar
        if (arg1.type == DOUBLE || arg2.type == DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() - arg2.getDouble());
        } else if (hasWideInteger(arg1, arg2)) {
            return integerResult(arg1.getBigint().subtract(arg2.getBigint()));
        } else {
            long a = arg1.getLong();
            long b = arg2.getLong();
            try {
                return getScalarInt(Math.subtractExact(a, b));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return integerResult(BigInteger.valueOf(a).subtract(BigInteger.valueOf(b)));
            }
        }
    }

    /**
     * Subtracts one RuntimeScalar from another with uninitialized value warnings.
     * Called when 'use warnings "uninitialized"' is in effect.
     *
     * @param arg1 The RuntimeScalar to subtract from.
     * @param arg2 The RuntimeScalar to subtract.
     * @return A new RuntimeScalar representing the difference.
     */
    public static RuntimeScalar subtractWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        return preserveStringChannel(subtractWarnUnpropagated(arg1, arg2), arg1, arg2)
                .propagateTaint(arg1, arg2);
    }

    private static RuntimeScalar subtractWarnUnpropagated(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Fast path: both INTEGER - skip blessedId check, getNumber(), type checks
        if (arg1.type == INTEGER && arg2.type == INTEGER) {
            if (hasWideInteger(arg1, arg2)) {
                return integerResult(arg1.getBigint().subtract(arg2.getBigint()));
            }
            long a = arg1.getLong();
            long b = arg2.getLong();
            try {
                return getScalarInt(Math.subtractExact(a, b));
            } catch (ArithmeticException ignored) {
                return integerResult(BigInteger.valueOf(a).subtract(BigInteger.valueOf(b)));
            }
        }

        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(-", "-");
            if (result != null) return result;
        }

        // Convert to number with warning for uninitialized values
        arg1 = numericOperandWarn(arg1, "subtraction (-)");
        arg2 = numericOperandWarn(arg2, "subtraction (-)");

        // Perform subtraction based on the type of RuntimeScalar
        if (arg1.type == DOUBLE || arg2.type == DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() - arg2.getDouble());
        } else if (hasWideInteger(arg1, arg2)) {
            return integerResult(arg1.getBigint().subtract(arg2.getBigint()));
        } else {
            long a = arg1.getLong();
            long b = arg2.getLong();
            try {
                return getScalarInt(Math.subtractExact(a, b));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return integerResult(BigInteger.valueOf(a).subtract(BigInteger.valueOf(b)));
            }
        }
    }

    /**
     * Multiplies two RuntimeScalar objects and returns the result.
     * Fast path - no warning checks.
     *
     * @param arg1 The first RuntimeScalar to multiply.
     * @param arg2 The second RuntimeScalar to multiply.
     * @return A new RuntimeScalar representing the product.
     */
    public static RuntimeScalar multiply(RuntimeScalar arg1, RuntimeScalar arg2) {
        return preserveStringChannel(multiplyUnpropagated(arg1, arg2), arg1, arg2)
                .propagateTaint(arg1, arg2);
    }

    private static RuntimeScalar multiplyUnpropagated(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Fast path: both INTEGER - skip blessedId check, getNumber(), type checks
        if (arg1.type == INTEGER && arg2.type == INTEGER) {
            if (hasWideInteger(arg1, arg2)) {
                return integerResult(arg1.getBigint().multiply(arg2.getBigint()));
            }
            long a = arg1.getLong();
            long b = arg2.getLong();
            try {
                return getScalarInt(Math.multiplyExact(a, b));
            } catch (ArithmeticException ignored) {
                return integerResult(BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)));
            }
        }

        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(*", "*");
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        arg1 = arg1.getNumber("multiplication (*)");
        arg2 = arg2.getNumber("multiplication (*)");
        // Perform multiplication based on the type of RuntimeScalar
        if (arg1.type == DOUBLE || arg2.type == DOUBLE) {
            RuntimeScalar exact = exactIntegralProduct(arg1, arg2);
            return exact != null ? exact
                    : new RuntimeScalar(arg1.getDouble() * arg2.getDouble());
        } else if (hasWideInteger(arg1, arg2)) {
            return integerResult(arg1.getBigint().multiply(arg2.getBigint()));
        } else {
            long a = arg1.getLong();
            long b = arg2.getLong();
            try {
                return getScalarInt(Math.multiplyExact(a, b));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return integerResult(BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)));
            }
        }
    }

    /**
     * Multiplies two RuntimeScalar objects with uninitialized value warnings.
     * Called when 'use warnings "uninitialized"' is in effect.
     *
     * @param arg1 The first RuntimeScalar to multiply.
     * @param arg2 The second RuntimeScalar to multiply.
     * @return A new RuntimeScalar representing the product.
     */
    public static RuntimeScalar multiplyWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        return multiplyWarnUnpropagated(arg1, arg2).propagateTaint(arg1, arg2);
    }

    private static RuntimeScalar multiplyWarnUnpropagated(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Fast path: both INTEGER - skip blessedId check, getNumber(), type checks
        if (arg1.type == INTEGER && arg2.type == INTEGER) {
            if (hasWideInteger(arg1, arg2)) {
                return integerResult(arg1.getBigint().multiply(arg2.getBigint()));
            }
            long a = arg1.getLong();
            long b = arg2.getLong();
            try {
                return getScalarInt(Math.multiplyExact(a, b));
            } catch (ArithmeticException ignored) {
                return integerResult(BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)));
            }
        }

        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(*", "*");
            if (result != null) return result;
        }

        // Convert to number with warning for uninitialized values
        arg1 = arg1.getNumberWarn("multiplication (*)");
        arg2 = arg2.getNumberWarn("multiplication (*)");

        // Perform multiplication based on the type of RuntimeScalar
        if (arg1.type == DOUBLE || arg2.type == DOUBLE) {
            RuntimeScalar exact = exactIntegralProduct(arg1, arg2);
            return exact != null ? exact
                    : new RuntimeScalar(arg1.getDouble() * arg2.getDouble());
        } else if (hasWideInteger(arg1, arg2)) {
            return integerResult(arg1.getBigint().multiply(arg2.getBigint()));
        } else {
            long a = arg1.getLong();
            long b = arg2.getLong();
            try {
                return getScalarInt(Math.multiplyExact(a, b));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return integerResult(BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)));
            }
        }
    }

    /**
     * Divides one RuntimeScalar by another and returns the result.
     * Fast path - no warning checks.
     *
     * @param arg1 The RuntimeScalar to divide.
     * @param arg2 The RuntimeScalar to divide by.
     * @return A new RuntimeScalar representing the quotient.
     * @throws PerlCompilerException if division by zero occurs.
     */
    public static RuntimeScalar divide(RuntimeScalar arg1, RuntimeScalar arg2) {
        return divideUnpropagated(arg1, arg2).propagateTaint(arg1, arg2);
    }

    private static RuntimeScalar divideUnpropagated(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(/", "/");
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        arg1 = arg1.getNumber("division (/)");
        arg2 = arg2.getNumber("division (/)");
        double divisor = arg2.getDouble();
        // Check for division by zero
        if (divisor == 0.0) {
            throw new PerlCompilerException("Illegal division by zero");
        }
        // Perform division
        double result = arg1.getDouble() / divisor;

        // Fix negative zero to positive zero
        if (result == 0.0 && Double.doubleToRawLongBits(result) == Double.doubleToRawLongBits(-0.0)) {
            result = 0.0;
        }

        return new RuntimeScalar(result);
    }

    /**
     * Divides one RuntimeScalar by another with uninitialized value warnings.
     * Called when 'use warnings "uninitialized"' is in effect.
     *
     * @param arg1 The RuntimeScalar to divide.
     * @param arg2 The RuntimeScalar to divide by.
     * @return A new RuntimeScalar representing the quotient.
     * @throws PerlCompilerException if division by zero occurs.
     */
    public static RuntimeScalar divideWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        return divideWarnUnpropagated(arg1, arg2).propagateTaint(arg1, arg2);
    }

    private static RuntimeScalar divideWarnUnpropagated(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(/", "/");
            if (result != null) return result;
        }

        // Convert to number with warning for uninitialized values
        arg1 = arg1.getNumberWarn("division (/)");
        arg2 = arg2.getNumberWarn("division (/)");
        double divisor = arg2.getDouble();
        // Check for division by zero
        if (divisor == 0.0) {
            throw new PerlCompilerException("Illegal division by zero");
        }
        // Perform division
        double result = arg1.getDouble() / divisor;

        // Fix negative zero to positive zero
        if (result == 0.0 && Double.doubleToRawLongBits(result) == Double.doubleToRawLongBits(-0.0)) {
            result = 0.0;
        }

        return new RuntimeScalar(result);
    }

    /**
     * Computes the modulus of one RuntimeScalar by another and returns the result.
     * Fast path - no warning checks.
     *
     * @param arg1 The RuntimeScalar to divide.
     * @param arg2 The RuntimeScalar to divide by.
     * @return A new RuntimeScalar representing the modulus.
     */
    public static RuntimeScalar modulus(RuntimeScalar arg1, RuntimeScalar arg2) {
        return modulusUnpropagated(arg1, arg2).propagateTaint(arg1, arg2);
    }

    private static RuntimeScalar modulusUnpropagated(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(%", "%");
            if (result != null) return result;
        }

        if (arg1.type == DOUBLE || arg2.type == DOUBLE) {
            return modulusFromDoubles(arg1.getDouble(), arg2.getDouble());
        }

        // Use long arithmetic to handle large integers (beyond int range)
        long dividend = arg1.getLong();
        long divisor = arg2.getLong();
        long result = dividend % divisor;

        // Adjust result for Perl-style modulus behavior
        // In Perl, the result has the same sign as the divisor
        if (result != 0 && ((divisor > 0 && result < 0) || (divisor < 0 && result > 0))) {
            result += divisor;
        }

        // Return as int if it fits, otherwise as long
        if (result >= Integer.MIN_VALUE && result <= Integer.MAX_VALUE) {
            return new RuntimeScalar((int) result);
        }
        return new RuntimeScalar(result);
    }

    /**
     * Computes the modulus of one RuntimeScalar by another with uninitialized value warnings.
     * Called when 'use warnings "uninitialized"' is in effect.
     *
     * @param arg1 The RuntimeScalar to divide.
     * @param arg2 The RuntimeScalar to divide by.
     * @return A new RuntimeScalar representing the modulus.
     */
    public static RuntimeScalar modulusWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        return modulusWarnUnpropagated(arg1, arg2).propagateTaint(arg1, arg2);
    }

    private static RuntimeScalar modulusWarnUnpropagated(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(%", "%");
            if (result != null) return result;
        }

        // Convert to number with warning for uninitialized values
        arg1 = arg1.getNumberWarn("modulus (%)");
        arg2 = arg2.getNumberWarn("modulus (%)");

        if (arg1.type == DOUBLE || arg2.type == DOUBLE) {
            return modulusFromDoubles(arg1.getDouble(), arg2.getDouble());
        }

        // Use long arithmetic to handle large integers (beyond int range)
        long dividend = arg1.getLong();
        long divisor = arg2.getLong();
        long result = dividend % divisor;

        // Adjust result for Perl-style modulus behavior
        // In Perl, the result has the same sign as the divisor
        if (result != 0 && ((divisor > 0 && result < 0) || (divisor < 0 && result > 0))) {
            result += divisor;
        }

        // Return as int if it fits, otherwise as long
        if (result >= Integer.MIN_VALUE && result <= Integer.MAX_VALUE) {
            return new RuntimeScalar((int) result);
        }
        return new RuntimeScalar(result);
    }

    /**
     * Compound assignment: +=
     * Checks for (+= overload first, then falls back to (+ overload.
     * Assigns the result back to the lvalue.
     *
     * @param arg1 The lvalue RuntimeScalar (will be modified).
     * @param arg2 The rvalue RuntimeScalar.
     * @return The modified arg1.
     */
    public static RuntimeScalar addAssign(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Check for (+= overload first
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(+=", "+=", "(+");
            if (result != null) {
                // Compound overload found - assign result back to lvalue
                arg1.set(result);
                return arg1;
            }
        }
        // Fall back to base operator (which already has (+ overload support)
        RuntimeScalar result = add(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Compound assignment: -=
     * Checks for (-= overload first, then falls back to (- overload.
     * Assigns the result back to the lvalue.
     *
     * @param arg1 The lvalue RuntimeScalar (will be modified).
     * @param arg2 The rvalue RuntimeScalar.
     * @return The modified arg1.
     */
    public static RuntimeScalar subtractAssign(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Check for (-= overload first
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(-=", "-=", "(-");
            if (result != null) {
                // Compound overload found - assign result back to lvalue
                arg1.set(result);
                return arg1;
            }
        }
        // Fall back to base operator (which already has (- overload support)
        RuntimeScalar result = subtract(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Compound assignment: *=
     * Checks for (*= overload first, then falls back to (* overload.
     * Assigns the result back to the lvalue.
     *
     * @param arg1 The lvalue RuntimeScalar (will be modified).
     * @param arg2 The rvalue RuntimeScalar.
     * @return The modified arg1.
     */
    public static RuntimeScalar multiplyAssign(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Check for (*= overload first
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(*=", "*=", "(*");
            if (result != null) {
                // Compound overload found - assign result back to lvalue
                arg1.set(result);
                return arg1;
            }
        }
        // Fall back to base operator (which already has (* overload support)
        RuntimeScalar result = multiply(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Compound assignment: /=
     * Checks for (/= overload first, then falls back to (/ overload.
     * Assigns the result back to the lvalue.
     *
     * @param arg1 The lvalue RuntimeScalar (will be modified).
     * @param arg2 The rvalue RuntimeScalar.
     * @return The modified arg1.
     */
    public static RuntimeScalar divideAssign(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Check for (/= overload first
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(/=", "/=", "(/");
            if (result != null) {
                // Compound overload found - assign result back to lvalue
                arg1.set(result);
                return arg1;
            }
        }
        // Fall back to base operator (which already has (/ overload support)
        RuntimeScalar result = divide(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Compound assignment: %=
     * Checks for (%= overload first, then falls back to (% overload.
     * Assigns the result back to the lvalue.
     *
     * @param arg1 The lvalue RuntimeScalar (will be modified).
     * @param arg2 The rvalue RuntimeScalar.
     * @return The modified arg1.
     */
    public static RuntimeScalar modulusAssign(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Check for (%= overload first
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(%=", "%=", "(%");
            if (result != null) {
                // Compound overload found - assign result back to lvalue
                arg1.set(result);
                return arg1;
            }
        }
        // Fall back to base operator (which already has (% overload support)
        RuntimeScalar result = modulus(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    // ========== WARN VARIANTS FOR COMPOUND ASSIGNMENT ==========
    // These are called when 'use warnings "uninitialized"' is in effect

    /**
     * Compound assignment: += with uninitialized value warnings.
     */
    public static RuntimeScalar addAssignWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(+=", "+=", "(+");
            if (result != null) {
                arg1.set(result);
                return arg1;
            }
        }
        RuntimeScalar result = addWarn(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Compound assignment: -= with uninitialized value warnings.
     */
    public static RuntimeScalar subtractAssignWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(-=", "-=", "(-");
            if (result != null) {
                arg1.set(result);
                return arg1;
            }
        }
        RuntimeScalar result = subtractWarn(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Compound assignment: *= with uninitialized value warnings.
     */
    public static RuntimeScalar multiplyAssignWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(*=", "*=", "(*");
            if (result != null) {
                arg1.set(result);
                return arg1;
            }
        }
        RuntimeScalar result = multiplyWarn(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Compound assignment: /= with uninitialized value warnings.
     */
    public static RuntimeScalar divideAssignWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(/=", "/=", "(/");
            if (result != null) {
                arg1.set(result);
                return arg1;
            }
        }
        RuntimeScalar result = divideWarn(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Compound assignment: %= with uninitialized value warnings.
     */
    public static RuntimeScalar modulusAssignWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(%=", "%=", "(%");
            if (result != null) {
                arg1.set(result);
                return arg1;
            }
        }
        RuntimeScalar result = modulusWarn(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Performs integer division operation on two RuntimeScalars.
     * This is used when "use integer" pragma is in effect.
     *
     * @param arg1 The dividend RuntimeScalar.
     * @param arg2 The divisor RuntimeScalar.
     * @return A new RuntimeScalar representing the integer division result.
     */
    public static RuntimeScalar integerDivide(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Like divide(): overloaded packages (Math::BigInt, Math::BigRat, …) still receive
        // operator dispatch under "use integer"; native IV division must not bypass overload.
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result =
                    OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(/", "/");
            if (result != null) {
                return result;
            }
        }

        long dividend = arg1.getLong();
        long divisor = arg2.getLong();

        if (divisor == 0) {
            throw new PerlCompilerException("Illegal division by zero");
        }

        long result = dividend / divisor;
        return new RuntimeScalar(result);
    }

    /**
     * Performs integer division with uninitialized value warnings.
     * This is used when "use integer" pragma is in effect and warnings are enabled.
     *
     * @param arg1 The dividend RuntimeScalar.
     * @param arg2 The divisor RuntimeScalar.
     * @return A new RuntimeScalar representing the integer division result.
     */
    public static RuntimeScalar integerDivideWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result =
                    OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(/", "/");
            if (result != null) {
                return result;
            }
        }

        // Convert to number with warning for uninitialized values
        arg1 = arg1.getNumberWarn("integer division (/)");
        arg2 = arg2.getNumberWarn("integer division (/)");
        long dividend = arg1.getLong();
        long divisor = arg2.getLong();

        if (divisor == 0) {
            throw new PerlCompilerException("Illegal division by zero");
        }

        long result = dividend / divisor;
        return new RuntimeScalar(result);
    }

    /**
     * Compound assignment: /= with uninitialized value warnings under "use integer".
     */
    public static RuntimeScalar integerDivideAssignWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(/=", "/=", "(/");
            if (result != null) {
                arg1.set(result);
                return arg1;
            }
        }
        RuntimeScalar result = integerDivideWarn(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Performs integer modulus operation on two RuntimeScalars.
     * This is used when "use integer" pragma is in effect.
     *
     * @param arg1 The RuntimeScalar to divide.
     * @param arg2 The RuntimeScalar to divide by.
     * @return A new RuntimeScalar representing the integer modulus.
     */
    public static RuntimeScalar integerModulus(RuntimeScalar arg1, RuntimeScalar arg2) {
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result =
                    OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(%", "%");
            if (result != null) {
                return result;
            }
        }

        long dividend = arg1.getLong();
        long divisor = arg2.getLong();

        if (divisor == 0) {
            throw new PerlCompilerException("Illegal modulus zero");
        }

        long result = dividend % divisor;
        return new RuntimeScalar(result);
    }

    /** Modulus when at least one operand is already a DOUBLE (see {@link #modulus}). */
    private static RuntimeScalar modulusFromDoubles(double dividend, double divisor) {
        if (divisor == 0.0) {
            throw new PerlCompilerException("Division by zero in modulus operation");
        }
        if (dividend == Math.rint(dividend) && divisor == Math.rint(divisor)
                && Math.abs(dividend) < MAX_EXACT_DOUBLE_INT && Math.abs(divisor) < MAX_EXACT_DOUBLE_INT) {
            long la = (long) dividend;
            long lb = (long) divisor;
            long result = la % lb;
            if (result != 0 && ((lb > 0 && result < 0) || (lb < 0 && result > 0))) {
                result += lb;
            }
            if (result >= Integer.MIN_VALUE && result <= Integer.MAX_VALUE) {
                return getScalarInt((int) result);
            }
            return new RuntimeScalar((double) result);
        }
        double result = truncate(dividend) % truncate(divisor);
        if (result != 0.0 && ((divisor > 0.0 && result < 0.0) || (divisor < 0.0 && result > 0.0))) {
            result += divisor;
        }
        return new RuntimeScalar(result);
    }

    private static double truncate(double value) {
        double result = (value >= 0) ? Math.floor(value) : Math.ceil(value);
        // Fix negative zero to positive zero
        if (result == 0.0 && Double.doubleToRawLongBits(result) == Double.doubleToRawLongBits(-0.0)) {
            result = 0.0;
        }
        return result;
    }

    /**
     * Computes the natural logarithm of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the logarithm for.
     * @return A new RuntimeScalar representing the natural logarithm.
     */
    public static RuntimeScalar log(RuntimeScalar runtimeScalar) {
        return logUnpropagated(runtimeScalar).propagateTaint(runtimeScalar);
    }

    private static RuntimeScalar logUnpropagated(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(log", "log", MathOperators::log);
            if (result != null) return result;
        }

        double v = runtimeScalar.getDouble();
        if (v == 0) {
            throw new PerlCompilerException("Can't take log of 0");
        }
        return new RuntimeScalar(Math.log(v));
    }

    /**
     * Computes the square root of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the square root for.
     * @return A new RuntimeScalar representing the square root.
     */
    public static RuntimeScalar sqrt(RuntimeScalar runtimeScalar) {
        return sqrtUnpropagated(runtimeScalar).propagateTaint(runtimeScalar);
    }

    private static RuntimeScalar sqrtUnpropagated(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(sqrt", "sqrt", MathOperators::sqrt);
            if (result != null) return result;
        }

        double d = runtimeScalar.getDouble();
        if (d < 0) {
            throw new PerlCompilerException("Can't take sqrt of " + ScalarUtils.formatLikePerl(d));
        }
        return new RuntimeScalar(Math.sqrt(d));
    }

    /**
     * Computes the cosine of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the cosine for.
     * @return A new RuntimeScalar representing the cosine.
     */
    public static RuntimeScalar cos(RuntimeScalar runtimeScalar) {
        return cosUnpropagated(runtimeScalar).propagateTaint(runtimeScalar);
    }

    private static RuntimeScalar cosUnpropagated(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(cos", "cos", MathOperators::cos);
            if (result != null) return result;
        }

        return new RuntimeScalar(Math.cos(runtimeScalar.getDouble()));
    }

    /**
     * Computes the sine of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the sine for.
     * @return A new RuntimeScalar representing the sine.
     */
    public static RuntimeScalar sin(RuntimeScalar runtimeScalar) {
        return sinUnpropagated(runtimeScalar).propagateTaint(runtimeScalar);
    }

    private static RuntimeScalar sinUnpropagated(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(sin", "sin", MathOperators::sin);
            if (result != null) return result;
        }

        return new RuntimeScalar(Math.sin(runtimeScalar.getDouble()));
    }

    /**
     * Computes the exponential of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the exponential for.
     * @return A new RuntimeScalar representing the exponential.
     */
    public static RuntimeScalar exp(RuntimeScalar runtimeScalar) {
        return expUnpropagated(runtimeScalar).propagateTaint(runtimeScalar);
    }

    private static RuntimeScalar expUnpropagated(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(exp", "exp", MathOperators::exp);
            if (result != null) return result;
        }

        return new RuntimeScalar(Math.exp(runtimeScalar.getDouble()));
    }

    /**
     * Raises a RuntimeScalar to the power of another RuntimeScalar.
     * Fast path - no warning checks.
     *
     * @param arg1 The base RuntimeScalar.
     * @param arg2 The exponent RuntimeScalar.
     * @return A new RuntimeScalar representing the power.
     */
    public static RuntimeScalar pow(RuntimeScalar arg1, RuntimeScalar arg2) {
        return powUnpropagated(arg1, arg2).propagateTaint(arg1, arg2);
    }

    private static RuntimeScalar powUnpropagated(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(**", "**");
            if (result != null) return result;
        }

        return new RuntimeScalar(Math.pow(arg1.getDouble(), arg2.getDouble()));
    }

    /**
     * Raises a RuntimeScalar to the power of another RuntimeScalar with uninitialized value warnings.
     * Called when 'use warnings "uninitialized"' is in effect.
     *
     * @param arg1 The base RuntimeScalar.
     * @param arg2 The exponent RuntimeScalar.
     * @return A new RuntimeScalar representing the power.
     */
    public static RuntimeScalar powWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        return powWarnUnpropagated(arg1, arg2).propagateTaint(arg1, arg2);
    }

    private static RuntimeScalar powWarnUnpropagated(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(**", "**");
            if (result != null) return result;
        }

        // Convert to number with warning for uninitialized values
        arg1 = arg1.getNumberWarn("exponentiation (**)");
        arg2 = arg2.getNumberWarn("exponentiation (**)");

        return new RuntimeScalar(Math.pow(arg1.getDouble(), arg2.getDouble()));
    }

    /**
     * Computes the angle theta (in radians) from the conversion of rectangular
     * coordinates (x, y) to polar coordinates (r, theta). This method returns
     * the angle whose tangent is the quotient of two specified numbers,
     * effectively calculating the arc-tangent of y/x.
     *
     * @param arg1 The y-coordinate as a RuntimeScalar.
     * @param arg2 The x-coordinate as a RuntimeScalar.
     * @return A new RuntimeScalar representing the angle theta in radians.
     */
    public static RuntimeScalar atan2(RuntimeScalar arg1, RuntimeScalar arg2) {
        return atan2Unpropagated(arg1, arg2).propagateTaint(arg1, arg2);
    }

    private static RuntimeScalar atan2Unpropagated(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(atan2", "atan2");
            if (result != null) return result;
        }

        return new RuntimeScalar(Math.atan2(arg1.getDouble(), arg2.getDouble()));
    }

    /**
     * Computes the absolute value of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the absolute value for.
     * @return A new RuntimeScalar representing the absolute value.
     */
    public static RuntimeScalar abs(RuntimeScalar runtimeScalar) {
        return absUnpropagated(runtimeScalar).propagateTaint(runtimeScalar);
    }

    private static RuntimeScalar absUnpropagated(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(abs", "abs", MathOperators::abs);
            if (result != null) return result;
        }

        RuntimeScalar arg1 = runtimeScalar;
        // Convert string type to number if necessary
        arg1 = arg1.getNumber("abs");
        // Compute absolute value based on the type of RuntimeScalar
        if (arg1.type == DOUBLE) {
            return new RuntimeScalar(Math.abs(arg1.getDouble()));
        } else {
            long v = arg1.getLong();
            if (v == Long.MIN_VALUE) {
                // Can't represent abs(Long.MIN_VALUE) as a signed long; Perl falls back to NV.
                return new RuntimeScalar(Math.abs((double) v));
            }
            return new RuntimeScalar(Math.abs(v));
        }
    }

    /**
     * Unary minus operator.
     * Fast path - no warning checks.
     */
    public static RuntimeScalar unaryMinus(RuntimeScalar runtimeScalar) {
        return unaryMinusUnpropagated(runtimeScalar).propagateTaint(runtimeScalar);
    }

    private static RuntimeScalar unaryMinusUnpropagated(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId,
                    "(neg", "neg", MathOperators::unaryMinus, "(-");
            if (result != null) return result;
        }

        RuntimeScalar stringResult = unaryMinusStringResult(runtimeScalar);
        if (stringResult != null) return stringResult;
        return subtract(getScalarInt(0), runtimeScalar);
    }

    /**
     * Unary minus operator with uninitialized value warnings.
     * Called when 'use warnings "uninitialized"' is in effect.
     */
    public static RuntimeScalar unaryMinusWarn(RuntimeScalar runtimeScalar) {
        return unaryMinusWarnUnpropagated(runtimeScalar).propagateTaint(runtimeScalar);
    }

    private static RuntimeScalar unaryMinusWarnUnpropagated(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId,
                    "(neg", "neg", MathOperators::unaryMinusWarn, "(-");
            if (result != null) return result;
        }

        if (runtimeScalar.isString()) {
            String input = runtimeScalar.toString();
            if (input.length() < 2) {
                if (input.isEmpty()) {
                    return getScalarInt(0);
                }
                if (input.equals("-")) {
                    return new RuntimeScalar("+");
                }
                if (input.equals("+")) {
                    return new RuntimeScalar("-");
                }
            }
            // Check if string has non-numeric trailing characters (not purely numeric)
            if (!input.matches("^\\s*[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?\\s*$")) {
                // String is not purely numeric
                if (input.startsWith("-")) {
                    return new RuntimeScalar("+" + input.substring(1));
                } else if (input.startsWith("+")) {
                    return new RuntimeScalar("-" + input.substring(1));
                } else if (input.matches("^[_A-Za-z].*")) {
                    return new RuntimeScalar("-" + input);
                }
            }
        }
        // Use subtractWarn to check for uninitialized values
        return subtractWarn(getScalarInt(0), runtimeScalar);
    }

    public static RuntimeScalar integer(RuntimeScalar arg1) {
        return integerUnpropagated(arg1).propagateTaint(arg1);
    }

    private static RuntimeScalar integerUnpropagated(RuntimeScalar arg1) {
        // Check if object is eligible for overloading
        int blessId = blessedId(arg1);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(arg1, blessId, "(int", "int", MathOperators::integer);
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        arg1 = arg1.getNumber("int");

        // Already an integer
        if (arg1.type == RuntimeScalarType.INTEGER) {
            return arg1;
        }

        // Handle DOUBLE type
        double value = arg1.getDouble();

        // Check for infinity and NaN values
        if (Double.isInfinite(value) || Double.isNaN(value)) {
            return new RuntimeScalar(value);  // Return infinity or NaN as-is
        }

        // Use truncate to get integer part (truncates towards zero)
        return new RuntimeScalar(truncate(value));
    }


    public static RuntimeScalar not(RuntimeScalar runtimeScalar) {
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            return Overload.bool_not(runtimeScalar);
        }
        return switch (runtimeScalar.type) {
            case INTEGER -> getScalarBoolean(runtimeScalar.getLong() == 0);
            case DOUBLE -> getScalarBoolean((double) runtimeScalar.value == 0.0);
            case STRING, BYTE_STRING -> {
                String s = (String) runtimeScalar.value;
                yield getScalarBoolean(s.isEmpty() || s.equals("0"));
            }
            case BOOLEAN -> getScalarBoolean(!(boolean) runtimeScalar.value);
            case GLOB -> scalarFalse;
            case REGEX -> scalarFalse;
            case JAVAOBJECT -> scalarFalse;
            case VSTRING -> scalarFalse;
            case TIED_SCALAR -> not(runtimeScalar.tiedFetch());
            default -> getScalarBoolean(!runtimeScalar.getBoolean());
        };
    }

    /** Logical negation for a lexical {@code no overloading} scope. */
    public static RuntimeScalar notNoOverload(RuntimeScalar runtimeScalar) {
        return runtimeScalar.getBooleanNoOverload() ? scalarFalse : scalarTrue;
    }

    // =====================================================================
    // NoOverload variants - used when 'no overloading' pragma is in effect.
    // These skip overload dispatch entirely and treat blessed references
    // as if unblessed (using refaddr-style numeric conversion).
    // =====================================================================

    private static RuntimeScalar arith(RuntimeScalar a, RuntimeScalar b, int op) {
        a = a.getNumberNoOverload();
        b = b.getNumberNoOverload();
        if (a.type == DOUBLE || b.type == DOUBLE) {
            double x = a.getDouble();
            double y = b.getDouble();
            return switch (op) {
                case 0 -> new RuntimeScalar(x + y);
                case 1 -> new RuntimeScalar(x - y);
                case 2 -> {
                    RuntimeScalar exact = exactIntegralProduct(a, b);
                    yield exact != null ? exact : new RuntimeScalar(x * y);
                }
                case 3 -> new RuntimeScalar(x / y);
                case 4 -> modulusFromDoubles(x, y);
                case 5 -> new RuntimeScalar(Math.pow(x, y));
                default -> throw new IllegalStateException();
            };
        }
        if (hasWideInteger(a, b) && op <= 2) {
            return switch (op) {
                case 0 -> integerResult(a.getBigint().add(b.getBigint()));
                case 1 -> integerResult(a.getBigint().subtract(b.getBigint()));
                case 2 -> integerResult(a.getBigint().multiply(b.getBigint()));
                default -> throw new IllegalStateException();
            };
        }
        long x = a.getLong();
        long y = b.getLong();
        try {
            return switch (op) {
                case 0 -> getScalarInt(Math.addExact(x, y));
                case 1 -> getScalarInt(Math.subtractExact(x, y));
                case 2 -> getScalarInt(Math.multiplyExact(x, y));
                case 3 -> y != 0 && x % y == 0
                        ? getScalarInt(x / y)
                        : new RuntimeScalar((double) x / (double) y);
                case 4 -> y != 0 ? getScalarInt(x % y)
                        : new RuntimeScalar((double) x % (double) y);
                case 5 -> new RuntimeScalar(Math.pow(x, y));
                default -> throw new IllegalStateException();
            };
        } catch (ArithmeticException ignored) {
            return switch (op) {
                case 0 -> integerResult(BigInteger.valueOf(x).add(BigInteger.valueOf(y)));
                case 1 -> integerResult(BigInteger.valueOf(x).subtract(BigInteger.valueOf(y)));
                case 2 -> integerResult(BigInteger.valueOf(x).multiply(BigInteger.valueOf(y)));
                case 3 -> new RuntimeScalar((double) x / (double) y);
                case 4 -> new RuntimeScalar((double) x % (double) y);
                case 5 -> new RuntimeScalar(Math.pow((double) x, (double) y));
                default -> throw new IllegalStateException();
            };
        }
    }

    public static RuntimeScalar addNoOverload(RuntimeScalar a, RuntimeScalar b)      { return arith(a, b, 0); }
    public static RuntimeScalar subtractNoOverload(RuntimeScalar a, RuntimeScalar b) { return arith(a, b, 1); }
    public static RuntimeScalar multiplyNoOverload(RuntimeScalar a, RuntimeScalar b) { return arith(a, b, 2); }
    public static RuntimeScalar divideNoOverload(RuntimeScalar a, RuntimeScalar b)   { return arith(a, b, 3); }
    public static RuntimeScalar modulusNoOverload(RuntimeScalar a, RuntimeScalar b)  { return arith(a, b, 4); }
    public static RuntimeScalar powNoOverload(RuntimeScalar a, RuntimeScalar b)      { return arith(a, b, 5); }

    public static RuntimeScalar unaryMinusNoOverload(RuntimeScalar a) {
        RuntimeScalar n = a.getNumberNoOverload();
        if (n.type == DOUBLE) return new RuntimeScalar(-n.getDouble());
        long v = n.getLong();
        try {
            return getScalarInt(Math.negateExact(v));
        } catch (ArithmeticException ignored) {
            return new RuntimeScalar(-(double) v);
        }
    }
}
