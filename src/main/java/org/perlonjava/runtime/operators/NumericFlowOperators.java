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

    private static boolean canUsePrimitive(RuntimeScalar left, RuntimeScalar right) {
        return left.type == RuntimeScalarType.INTEGER && right.type == RuntimeScalarType.INTEGER
                && !left.isTainted() && !right.isTainted()
                // RuntimeScalar represents both Long and BigInteger as INTEGER.
                // getLong() on the latter truncates, so only accept the two
                // fixed-width payload forms supported by this first slice.
                && isFixedWidthInteger(left.value) && isFixedWidthInteger(right.value);
    }

    private static boolean isFixedWidthInteger(Object value) {
        return value instanceof Integer || value instanceof Long;
    }
}
