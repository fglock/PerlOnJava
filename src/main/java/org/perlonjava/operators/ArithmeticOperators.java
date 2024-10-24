package org.perlonjava.operators;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarType;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;

/**
 * Provides basic arithmetic operations for RuntimeScalar objects.
 * This class includes methods for addition, subtraction, multiplication,
 * division, and modulus operations.
 */
public class ArithmeticOperators {

    /**
     * Adds an integer to a RuntimeScalar and returns the result.
     *
     * @param arg1 The RuntimeScalar to add to.
     * @param arg2 The integer value to add.
     * @return A new RuntimeScalar representing the sum.
     */
    public static RuntimeScalar add(RuntimeScalar arg1, int arg2) {
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() + arg2);
        } else {
            return getScalarInt(arg1.getInt() + arg2);
        }
    }

    /**
     * Adds two RuntimeScalar objects and returns the result.
     *
     * @param runtimeScalar The first RuntimeScalar to add.
     * @param arg2          The second RuntimeScalar to add.
     * @return A new RuntimeScalar representing the sum.
     */
    public static RuntimeScalar add(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() + arg2.getDouble());
        } else {
            return getScalarInt(arg1.getInt() + arg2.getInt());
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
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() - arg2);
        } else {
            return getScalarInt(arg1.getInt() - arg2);
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
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() - arg2.getDouble());
        } else {
            return getScalarInt(arg1.getInt() - arg2.getInt());
        }
    }

    /**
     * Multiplies two RuntimeScalar objects and returns the result.
     *
     * @param arg1 The first RuntimeScalar to multiply.
     * @param arg2 The second RuntimeScalar to multiply.
     * @return A new RuntimeScalar representing the product.
     */
    public static RuntimeScalar multiply(RuntimeScalar arg1, RuntimeScalar arg2) {
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() * arg2.getDouble());
        } else {
            return getScalarInt((long) arg1.getInt() * (long) arg2.getInt());
        }
    }

    /**
     * Divides one RuntimeScalar by another and returns the result.
     *
     * @param arg1 The RuntimeScalar to divide.
     * @param arg2 The RuntimeScalar to divide by.
     * @return A new RuntimeScalar representing the quotient.
     * @throws PerlCompilerException if division by zero occurs.
     */
    public static RuntimeScalar divide(RuntimeScalar arg1, RuntimeScalar arg2) {
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        double divisor = arg2.getDouble();
        if (divisor == 0.0) {
            throw new PerlCompilerException("Illegal division by zero");
        }
        return new RuntimeScalar(arg1.getDouble() / divisor);
    }

    /**
     * Computes the modulus of one RuntimeScalar by another and returns the result.
     *
     * @param arg1 The RuntimeScalar to divide.
     * @param arg2 The RuntimeScalar to divide by.
     * @return A new RuntimeScalar representing the modulus.
     */
    public static RuntimeScalar modulus(RuntimeScalar arg1, RuntimeScalar arg2) {
        int divisor = arg2.getInt();
        int result = arg1.getInt() % divisor;
        if (result != 0.0 && ((divisor > 0.0 && result < 0.0) || (divisor < 0.0 && result > 0.0))) {
            result += divisor;
        }
        return new RuntimeScalar(result);
    }
}
