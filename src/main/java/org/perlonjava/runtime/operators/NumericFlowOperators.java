package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

/** Runtime guards and primitive fast paths for compiler-proven numeric flows. */
public final class NumericFlowOperators {
    private NumericFlowOperators() {}

    public static RuntimeScalar assignAdd(RuntimeScalar target, RuntimeScalar left, RuntimeScalar right) {
        if (canUsePrimitive(left, right)) {
            try {
                return target.set(Math.addExact(left.getLong(), right.getLong()));
            } catch (ArithmeticException ignored) {
                // Preserve the normal operator's wide-integer/unsigned-IV result.
            }
        }
        return target.set(MathOperators.add(left, right));
    }

    public static RuntimeScalar assignSubtract(RuntimeScalar target, RuntimeScalar left, RuntimeScalar right) {
        if (canUsePrimitive(left, right)) {
            try {
                return target.set(Math.subtractExact(left.getLong(), right.getLong()));
            } catch (ArithmeticException ignored) {
                // Preserve the normal operator's wide-integer/unsigned-IV result.
            }
        }
        return target.set(MathOperators.subtract(left, right));
    }

    public static RuntimeScalar assignMultiply(RuntimeScalar target, RuntimeScalar left, RuntimeScalar right) {
        if (canUsePrimitive(left, right)) {
            try {
                return target.set(Math.multiplyExact(left.getLong(), right.getLong()));
            } catch (ArithmeticException ignored) {
                // Preserve the normal operator's wide-integer/unsigned-IV result.
            }
        }
        return target.set(MathOperators.multiply(left, right));
    }

    public static RuntimeScalar assignModulus(RuntimeScalar target, RuntimeScalar left, RuntimeScalar right) {
        if (canUsePrimitive(left, right)) {
            long divisor = right.getLong();
            if (divisor != 0) return target.set(left.getLong() % divisor);
        }
        return target.set(MathOperators.modulus(left, right));
    }

    /**
     * Assign {@code (multiplyLeft * multiplyRight + addend) % divisor} without
     * materializing the multiply and add result cells when the entire numeric
     * expression is a fixed-width, untainted integer flow.
     */
    public static RuntimeScalar assignMultiplyAddModulus(RuntimeScalar target,
                                                           RuntimeScalar multiplyLeft,
                                                           RuntimeScalar multiplyRight,
                                                           RuntimeScalar addend,
                                                           RuntimeScalar divisor) {
        if (canUsePrimitive(multiplyLeft, multiplyRight)
                && canUsePrimitive(addend, divisor)) {
            try {
                long product = Math.multiplyExact(multiplyLeft.getLong(), multiplyRight.getLong());
                long sum = Math.addExact(product, addend.getLong());
                long modulus = divisor.getLong();
                if (modulus != 0) return target.set(sum % modulus);
            } catch (ArithmeticException ignored) {
                // Preserve wide-integer behavior through the ordinary chain.
            }
        }
        return target.set(MathOperators.modulus(
                MathOperators.add(MathOperators.multiply(multiplyLeft, multiplyRight), addend), divisor));
    }

    /** Assign {@code (left + right) % divisor} without an intermediate result cell. */
    public static RuntimeScalar assignAddModulus(RuntimeScalar target, RuntimeScalar left,
                                                  RuntimeScalar right, RuntimeScalar divisor) {
        if (canUsePrimitive(left, right) && canUsePrimitive(right, divisor)) {
            try {
                long sum = Math.addExact(left.getLong(), right.getLong());
                long modulus = divisor.getLong();
                if (modulus != 0) return target.set(sum % modulus);
            } catch (ArithmeticException ignored) {
                // Preserve wide-integer behavior through the ordinary chain.
            }
        }
        return target.set(MathOperators.modulus(MathOperators.add(left, right), divisor));
    }

    public static RuntimeScalar assignMultiplyAddModulusPrimitive(RuntimeScalar target,
            RuntimeScalar multiplyLeft, RuntimeScalar multiplyRight, RuntimeScalar addend,
            RuntimeScalar divisor) {
        if (canUsePrimitive(multiplyLeft, multiplyRight) && canUsePrimitive(addend, divisor)) {
            try {
                long product = Math.multiplyExact(multiplyLeft.getLong(), multiplyRight.getLong());
                long sum = Math.addExact(product, addend.getLong());
                long modulus = divisor.getLong();
                if (modulus != 0) return target.setPrimitiveFlowInteger(sum % modulus);
            } catch (ArithmeticException ignored) { }
        }
        target.flushPrimitiveFlowInteger();
        return assignMultiplyAddModulus(target, multiplyLeft, multiplyRight, addend, divisor);
    }

    public static RuntimeScalar assignAddModulusPrimitive(RuntimeScalar target, RuntimeScalar left,
            RuntimeScalar right, RuntimeScalar divisor) {
        if (canUsePrimitive(left, right) && canUsePrimitive(right, divisor)) {
            try {
                long sum = Math.addExact(left.getLong(), right.getLong());
                long modulus = divisor.getLong();
                if (modulus != 0) return target.setPrimitiveFlowInteger(sum % modulus);
            } catch (ArithmeticException ignored) { }
        }
        target.flushPrimitiveFlowInteger();
        return assignAddModulus(target, left, right, divisor);
    }

    private static boolean canUsePrimitive(RuntimeScalar left, RuntimeScalar right) {
        return left.type == RuntimeScalarType.INTEGER && right.type == RuntimeScalarType.INTEGER
                && !left.isTainted() && !right.isTainted()
                // RuntimeScalar represents both Long and BigInteger as INTEGER.
                // getLong() on the latter truncates, so only accept the two
                // fixed-width payload forms supported by this first slice.
                && isFixedWidthInteger(left.value) && isFixedWidthInteger(right.value);
    }

    /**
     * A single staged {@code use integer} bitwise-tree leaf can remain in a JVM
     * long only when the scalar is already an ordinary, untainted native IV.
     * Callers preserve the normal operator path for every other value.
     */
    public static boolean canUseNativeBitwiseValue(RuntimeScalar scalar) {
        return scalar != null && scalar.type == RuntimeScalarType.INTEGER
                && !scalar.isTainted() && isFixedWidthInteger(scalar.value);
    }

    /** Match {@link BitwiseOperators#integerShiftLeft(RuntimeScalar, RuntimeScalar)} for native IVs. */
    public static long integerShiftLeftNative(long value, long shift) {
        if (shift < 0) {
            shift = -shift;
            if (shift < 0 || shift >= 64) return value < 0 ? -1 : 0;
            return value >> (int) shift;
        }
        if (shift >= 64) return 0;
        return value << (int) shift;
    }

    /** Match {@link BitwiseOperators#integerShiftRight(RuntimeScalar, RuntimeScalar)} for native IVs. */
    public static long integerShiftRightNative(long value, long shift) {
        if (shift < 0) {
            shift = -shift;
            if (shift < 0 || shift >= 64) return 0;
            return value << (int) shift;
        }
        if (shift >= 64) return value < 0 ? -1 : 0;
        return value >> (int) shift;
    }

    private static boolean isFixedWidthInteger(Object value) {
        return value instanceof Integer || value instanceof Long;
    }
}
