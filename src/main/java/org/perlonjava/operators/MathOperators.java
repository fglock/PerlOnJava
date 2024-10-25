package org.perlonjava.operators;

import org.perlonjava.parser.NumberParser;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarType;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;

/**
 * Provides basic arithmetic operations for RuntimeScalar objects.
 * This class includes methods for addition, subtraction, multiplication,
 * division, modulus, and various mathematical functions.
 */
public class MathOperators {

    /**
     * Adds an integer to a RuntimeScalar and returns the result.
     *
     * @param arg1 The RuntimeScalar to add to.
     * @param arg2 The integer value to add.
     * @return A new RuntimeScalar representing the sum.
     */
    public static RuntimeScalar add(RuntimeScalar arg1, int arg2) {
        // Convert string type to number if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        // Perform addition based on the type of RuntimeScalar
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
        // Convert string type to number if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = NumberParser.parseNumber(arg2);
        }
        // Perform addition based on the type of RuntimeScalar
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
        // Convert string type to number if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        // Perform subtraction based on the type of RuntimeScalar
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
        // Convert string type to number if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = NumberParser.parseNumber(arg2);
        }
        // Perform subtraction based on the type of RuntimeScalar
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
        // Convert string type to number if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = NumberParser.parseNumber(arg2);
        }
        // Perform multiplication based on the type of RuntimeScalar
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
        // Convert string type to number if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = NumberParser.parseNumber(arg2);
        }
        double divisor = arg2.getDouble();
        // Check for division by zero
        if (divisor == 0.0) {
            throw new PerlCompilerException("Illegal division by zero");
        }
        // Perform division
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
        // Adjust result for negative modulus
        if (result != 0.0 && ((divisor > 0.0 && result < 0.0) || (divisor < 0.0 && result > 0.0))) {
            result += divisor;
        }
        return new RuntimeScalar(result);
    }

    /**
     * Computes the natural logarithm of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the logarithm for.
     * @return A new RuntimeScalar representing the natural logarithm.
     */
    public static RuntimeScalar log(RuntimeScalar runtimeScalar) {
        return new RuntimeScalar(Math.log(runtimeScalar.getDouble()));
    }

    /**
     * Computes the square root of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the square root for.
     * @return A new RuntimeScalar representing the square root.
     */
    public static RuntimeScalar sqrt(RuntimeScalar runtimeScalar) {
        return new RuntimeScalar(Math.sqrt(runtimeScalar.getDouble()));
    }

    /**
     * Computes the cosine of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the cosine for.
     * @return A new RuntimeScalar representing the cosine.
     */
    public static RuntimeScalar cos(RuntimeScalar runtimeScalar) {
        return new RuntimeScalar(Math.cos(runtimeScalar.getDouble()));
    }

    /**
     * Computes the sine of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the sine for.
     * @return A new RuntimeScalar representing the sine.
     */
    public static RuntimeScalar sin(RuntimeScalar runtimeScalar) {
        return new RuntimeScalar(Math.sin(runtimeScalar.getDouble()));
    }

    /**
     * Computes the exponential of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the exponential for.
     * @return A new RuntimeScalar representing the exponential.
     */
    public static RuntimeScalar exp(RuntimeScalar runtimeScalar) {
        return new RuntimeScalar(Math.exp(runtimeScalar.getDouble()));
    }

    /**
     * Raises a RuntimeScalar to the power of another RuntimeScalar.
     *
     * @param runtimeScalar The base RuntimeScalar.
     * @param arg           The exponent RuntimeScalar.
     * @return A new RuntimeScalar representing the power.
     */
    public static RuntimeScalar pow(RuntimeScalar runtimeScalar, RuntimeScalar arg) {
        return new RuntimeScalar(Math.pow(runtimeScalar.getDouble(), arg.getDouble()));
    }

    /**
     * Computes the angle theta (in radians) from the conversion of rectangular
     * coordinates (x, y) to polar coordinates (r, theta). This method returns
     * the angle whose tangent is the quotient of two specified numbers,
     * effectively calculating the arc-tangent of y/x.
     *
     * @param runtimeScalar The y-coordinate as a RuntimeScalar.
     * @param arg           The x-coordinate as a RuntimeScalar.
     * @return A new RuntimeScalar representing the angle theta in radians.
     */
    public static RuntimeScalar atan2(RuntimeScalar runtimeScalar, RuntimeScalar arg) {
        return new RuntimeScalar(Math.atan2(runtimeScalar.getDouble(), arg.getDouble()));
    }

    /**
     * Computes the absolute value of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the absolute value for.
     * @return A new RuntimeScalar representing the absolute value.
     */
    public static RuntimeScalar abs(RuntimeScalar runtimeScalar) {
        RuntimeScalar arg1 = runtimeScalar;
        // Convert string type to number if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        // Compute absolute value based on the type of RuntimeScalar
        if (arg1.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(Math.abs(arg1.getDouble()));
        } else {
            return new RuntimeScalar(Math.abs(arg1.getInt()));
        }
    }
}